use serde::{Deserialize, Serialize};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use thiserror::Error;
use wait_timeout::ChildExt;

#[derive(Error, Debug, PartialEq)]
pub enum SandboxError {
    #[error("PRoot binary not found at: {0}")]
    BinaryNotFound(String),
    #[error("Rootfs directory not found at: {0}")]
    RootfsNotFound(String),
    #[error("Workspace directory not found at: {0}")]
    WorkspaceNotFound(String),
    #[error("Command execution failed: {0}")]
    ExecutionFailed(String),
    #[error("Process timed out after {0} seconds")]
    Timeout(u64),
    #[error("Command output exceeded {0} bytes limit")]
    OutputLimitExceeded(usize),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ExecutionResult {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    #[serde(default)]
    pub timed_out: bool,
    #[serde(default)]
    pub stdout_truncated: bool,
    #[serde(default)]
    pub stderr_truncated: bool,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sandbox_mode: Option<String>,
}

#[derive(Debug, Clone)]
pub struct PRootConfig {
    pub proot_path: PathBuf,
    pub rootfs_path: PathBuf,
    pub workspace_path: PathBuf,
    pub default_working_dir: String,
    /// Shell executed inside the sandbox. `/bin/sh` when the rootfs provides one,
    /// otherwise `/system/bin/sh` with `/system` bind-mounted into the guest.
    pub shell_path: String,
    pub extra_bindings: Vec<(PathBuf, String)>,
    pub timeout_secs: u64,
    pub max_output_bytes: usize,
}

pub const DEFAULT_TIMEOUT_SECS: u64 = 120;
pub const DEFAULT_MAX_OUTPUT_BYTES: usize = 256 * 1024; // 256 KiB per stream

/// Android system trees bound into the guest so a bundled real PRoot binary can
/// run the platform's toybox/bionic userland without shipping a second rootfs.
pub const SYSTEM_BIND_PATHS: [&str; 3] = ["/system", "/vendor", "/apex"];

impl PRootConfig {
    pub fn new<P: AsRef<Path>>(proot: P, rootfs: P, workspace: P) -> Self {
        Self {
            proot_path: proot.as_ref().to_path_buf(),
            rootfs_path: rootfs.as_ref().to_path_buf(),
            workspace_path: workspace.as_ref().to_path_buf(),
            default_working_dir: "/workspace".to_string(),
            shell_path: "/bin/sh".to_string(),
            extra_bindings: Vec::new(),
            timeout_secs: DEFAULT_TIMEOUT_SECS,
            max_output_bytes: DEFAULT_MAX_OUTPUT_BYTES,
        }
    }

    pub fn with_binding<P: AsRef<Path>>(mut self, host_path: P, guest_path: &str) -> Self {
        self.extra_bindings
            .push((host_path.as_ref().to_path_buf(), guest_path.to_string()));
        self
    }

    pub fn with_shell_path(mut self, shell_path: &str) -> Self {
        self.shell_path = shell_path.to_string();
        self
    }

    pub fn with_timeout_secs(mut self, timeout_secs: u64) -> Self {
        self.timeout_secs = timeout_secs.max(1);
        self
    }

    pub fn with_max_output_bytes(mut self, max_output_bytes: usize) -> Self {
        self.max_output_bytes = max_output_bytes.max(1024);
        self
    }
}

pub struct PRootExecutor {
    config: PRootConfig,
}

impl PRootExecutor {
    pub fn new(config: PRootConfig) -> Self {
        Self { config }
    }

    /// Validates that configured directories and binaries exist.
    pub fn validate(&self) -> Result<(), SandboxError> {
        if !self.config.proot_path.exists() {
            return Err(SandboxError::BinaryNotFound(
                self.config.proot_path.to_string_lossy().to_string(),
            ));
        }
        if !self.config.rootfs_path.exists() {
            return Err(SandboxError::RootfsNotFound(
                self.config.rootfs_path.to_string_lossy().to_string(),
            ));
        }
        if !self.config.workspace_path.exists() {
            return Err(SandboxError::WorkspaceNotFound(
                self.config.workspace_path.to_string_lossy().to_string(),
            ));
        }
        Ok(())
    }

    /// Constructs the exact argument list passed to PRoot.
    pub fn build_args(&self, command_str: &str) -> Vec<String> {
        let mut args = vec![
            "-r".to_string(),
            self.config.rootfs_path.to_string_lossy().to_string(),
            "-0".to_string(),
        ];

        // Bind the Android platform userland read-only sources into the guest so
        // a minimal rootfs can still resolve /system/bin/sh and toybox tools.
        for host in SYSTEM_BIND_PATHS {
            if Path::new(host).exists() {
                args.push("-b".to_string());
                args.push(format!("{}:{}", host, host));
            }
        }

        args.push("-b".to_string());
        args.push("/dev".to_string());
        args.push("-b".to_string());
        args.push("/proc".to_string());
        args.push("-b".to_string());
        args.push(format!(
            "{}:{}",
            self.config.workspace_path.to_string_lossy(),
            self.config.default_working_dir
        ));

        for (host, guest) in &self.config.extra_bindings {
            args.push("-b".to_string());
            args.push(format!("{}:{}", host.to_string_lossy(), guest));
        }

        args.push("-w".to_string());
        args.push(self.config.default_working_dir.clone());
        args.push(self.config.shell_path.clone());
        args.push("-c".to_string());
        args.push(command_str.to_string());

        args
    }

    /// Detects whether the configured proot binary is a shim/fallback script
    /// rather than a real proot binary. Shims cannot parse proot-style args.
    fn is_shim(&self) -> bool {
        is_shim_path(&self.config.proot_path)
    }

    pub fn sandbox_mode(&self) -> &'static str {
        if self.is_shim() {
            "shim"
        } else {
            "proot"
        }
    }

    /// Executes a shell command inside the sandbox.
    /// Uses real proot when available; falls back to direct execution via sh
    /// in workspace dir when the proot path is an OpenBot shim.
    pub fn execute(&self, command_str: &str) -> Result<ExecutionResult, SandboxError> {
        self.validate()?;
        let mode = self.sandbox_mode().to_string();
        let start = std::time::Instant::now();

        let mut command = if self.is_shim() {
            // Shim mode: execute directly with /system/bin/sh or sh in workspace.
            // This is a degraded sandbox (no rootfs isolation) but keeps commands working.
            let shell = if Path::new("/system/bin/sh").exists() {
                "/system/bin/sh"
            } else {
                "/bin/sh"
            };
            let mut cmd = Command::new(shell);
            cmd.arg("-c")
                .arg(command_str)
                .current_dir(&self.config.workspace_path)
                .env("HOME", self.config.workspace_path.to_string_lossy().as_ref())
                .env(
                    "TMPDIR",
                    self.config.rootfs_path.join("tmp").to_string_lossy().as_ref(),
                )
                .env("PATH", "/system/bin:/system/xbin:/vendor/bin:/usr/bin:/bin");
            cmd
        } else {
            let args = self.build_args(command_str);
            let mut cmd = Command::new(&self.config.proot_path);
            cmd.args(&args);
            cmd
        };
        command.stdout(Stdio::piped()).stderr(Stdio::piped());

        // Put the child in its own process group so a timeout can also terminate
        // descendants that inherited the stdout/stderr pipes.
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }

        let mut child = command
            .spawn()
            .map_err(|e| SandboxError::ExecutionFailed(e.to_string()))?;
        let child_pid = child.id();

        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| SandboxError::ExecutionFailed("failed to capture stdout".to_string()))?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| SandboxError::ExecutionFailed("failed to capture stderr".to_string()))?;

        let max = self.config.max_output_bytes;
        let stdout_sink = spawn_capped_reader(stdout, max);
        let stderr_sink = spawn_capped_reader(stderr, max);

        let timeout = Duration::from_secs(self.config.timeout_secs);
        let timed_out;
        let status = match child
            .wait_timeout(timeout)
            .map_err(|e| SandboxError::ExecutionFailed(e.to_string()))?
        {
            Some(status) => {
                timed_out = false;
                status
            }
            None => {
                timed_out = true;
                let _ = child.kill();
                kill_process_group(child_pid);
                child
                    .wait()
                    .map_err(|e| SandboxError::ExecutionFailed(e.to_string()))?
            }
        };

        // Readers are intentionally detached rather than joined: an escaped
        // grandchild could keep a pipe open forever and hang the UI thread.
        wait_for_readers(&[&stdout_sink, &stderr_sink]);
        let (stdout_bytes, stdout_truncated) = snapshot_output(&stdout_sink);
        let (stderr_bytes, stderr_truncated) = snapshot_output(&stderr_sink);

        let stdout_str = String::from_utf8_lossy(&stdout_bytes).to_string();
        let mut stderr_str = String::from_utf8_lossy(&stderr_bytes).to_string();
        if timed_out {
            stderr_str.push_str(&format!(
                "\n[openbot] command timed out after {}s\n",
                self.config.timeout_secs
            ));
        }

        Ok(ExecutionResult {
            exit_code: if timed_out {
                -1
            } else {
                status.code().unwrap_or(-1)
            },
            stdout: stdout_str,
            stderr: stderr_str,
            timed_out,
            stdout_truncated,
            stderr_truncated,
            duration_ms: start.elapsed().as_millis() as u64,
            sandbox_mode: Some(mode),
        })
    }
}

#[derive(Default)]
struct CappedOutput {
    data: Vec<u8>,
    truncated: bool,
}

fn lock_output(sink: &Arc<Mutex<CappedOutput>>) -> std::sync::MutexGuard<'_, CappedOutput> {
    match sink.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

/// Drains a pipe into a shared, size-capped buffer. The thread is detached so a
/// command that spawns a background process cannot block execution forever.
fn spawn_capped_reader<R: Read + Send + 'static>(
    mut reader: R,
    max_bytes: usize,
) -> Arc<Mutex<CappedOutput>> {
    let shared = Arc::new(Mutex::new(CappedOutput::default()));
    let sink = Arc::clone(&shared);
    std::thread::spawn(move || {
        let mut chunk = [0u8; 8192];
        loop {
            match reader.read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => {
                    let mut out = lock_output(&sink);
                    if out.data.len() < max_bytes {
                        let remaining = max_bytes - out.data.len();
                        let take = remaining.min(n);
                        out.data.extend_from_slice(&chunk[..take]);
                        if take < n {
                            out.truncated = true;
                        }
                    } else {
                        out.truncated = true;
                    }
                }
                Err(_) => break,
            }
        }
    });
    shared
}

fn snapshot_output(sink: &Arc<Mutex<CappedOutput>>) -> (Vec<u8>, bool) {
    let out = lock_output(sink);
    (out.data.clone(), out.truncated)
}

/// Wait until both readers stop growing (or a short deadline passes) so the
/// final output is not truncated just because the child exited first.
fn wait_for_readers(sinks: &[&Arc<Mutex<CappedOutput>>]) {
    let mut last_total = usize::MAX;
    for _ in 0..20 {
        std::thread::sleep(Duration::from_millis(10));
        let total: usize = sinks.iter().map(|sink| lock_output(sink).data.len()).sum();
        if total == last_total {
            break;
        }
        last_total = total;
    }
}

/// Best-effort termination of the child's process group when the timeout fires.
fn kill_process_group(pid: u32) {
    let _ = Command::new("kill")
        .arg("-KILL")
        .arg(format!("-{}", pid))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

pub fn is_shim_path(path: &Path) -> bool {
    std::fs::read_to_string(path)
        .map(|contents| contents.contains("OPENBOT_SHIM"))
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_proot_argument_construction() {
        let proot = PathBuf::from("/data/data/com.openbot/proot");
        let rootfs = PathBuf::from("/data/data/com.openbot/rootfs");
        let workspace = PathBuf::from("/data/data/com.openbot/workspace");

        let config = PRootConfig::new(&proot, &rootfs, &workspace)
            .with_binding("/data/data/com.openbot/cache", "/tmp");

        let executor = PRootExecutor::new(config);
        let args = executor.build_args("echo 'hello from openbot'");

        assert_eq!(args[0], "-r");
        assert_eq!(args[1], "/data/data/com.openbot/rootfs");
        assert_eq!(args[2], "-0");
        assert!(args.contains(&"-b".to_string()));
        assert!(args.contains(&"/dev".to_string()));
        assert!(args.contains(&"/proc".to_string()));
        assert!(args.contains(&"/data/data/com.openbot/workspace:/workspace".to_string()));
        assert!(args.contains(&"/data/data/com.openbot/cache:/tmp".to_string()));
        assert_eq!(args[args.len() - 1], "echo 'hello from openbot'");
        assert!(args.contains(&"/bin/sh".to_string()));
        assert!(args.contains(&"-c".to_string()));
    }

    #[test]
    fn test_real_mode_uses_configured_shell() {
        let config = PRootConfig::new("/proot", "/rootfs", "/ws")
            .with_shell_path("/system/bin/sh");
        let args = PRootExecutor::new(config).build_args("id");
        let shell_index = args
            .iter()
            .position(|arg| arg == "/system/bin/sh")
            .expect("configured shell must be present");
        assert_eq!(args[shell_index + 1], "-c");
        assert_eq!(args[shell_index + 2], "id");
    }

    #[test]
    fn test_sandbox_mode_is_proot_for_non_shim_binary() {
        let temp = tempdir().unwrap();
        let proot_file = temp.path().join("proot");
        std::fs::write(&proot_file, [0x7f, b'E', b'L', b'F', 0, 0, 0, 0]).unwrap();
        let rootfs = temp.path().join("rootfs");
        std::fs::create_dir(&rootfs).unwrap();
        let ws = temp.path().join("workspace");
        std::fs::create_dir(&ws).unwrap();
        let executor = PRootExecutor::new(PRootConfig::new(&proot_file, &rootfs, &ws));
        assert_eq!(executor.sandbox_mode(), "proot");
    }

    #[test]
    fn test_validation_fails_on_missing_paths() {
        let config =
            PRootConfig::new("/nonexistent/proot", "/nonexistent/rootfs", "/nonexistent/ws");
        let executor = PRootExecutor::new(config);
        assert!(matches!(
            executor.validate(),
            Err(SandboxError::BinaryNotFound(_))
        ));
    }

    #[test]
    fn test_validation_succeeds_on_real_dirs() {
        let temp = tempdir().unwrap();
        let proot_file = temp.path().join("proot");
        std::fs::write(&proot_file, b"#!/bin/sh\n").unwrap();
        let rootfs = temp.path().join("rootfs");
        std::fs::create_dir(&rootfs).unwrap();
        let ws = temp.path().join("workspace");
        std::fs::create_dir(&ws).unwrap();

        let config = PRootConfig::new(&proot_file, &rootfs, &ws);
        let executor = PRootExecutor::new(config);
        assert!(executor.validate().is_ok());
    }

    #[test]
    fn test_shim_mode_executes_directly() {
        let temp = tempdir().unwrap();
        let proot_file = temp.path().join("proot");
        std::fs::write(&proot_file, b"#!/bin/sh\n# OPENBOT_SHIM\nexit 0\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&proot_file).unwrap().permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&proot_file, perms).unwrap();
        }
        let rootfs = temp.path().join("rootfs");
        std::fs::create_dir_all(rootfs.join("tmp")).unwrap();
        let ws = temp.path().join("workspace");
        std::fs::create_dir(&ws).unwrap();

        let config = PRootConfig::new(&proot_file, &rootfs, &ws);
        let executor = PRootExecutor::new(config);
        let result = executor.execute("echo hello-shim").unwrap();
        assert_eq!(result.exit_code, 0);
        assert!(result.stdout.contains("hello-shim"));
        assert_eq!(result.sandbox_mode.as_deref(), Some("shim"));
        assert!(!result.timed_out);
    }

    fn shim_executor(temp: &Path) -> PRootExecutor {
        let proot_file = temp.join("proot");
        std::fs::write(&proot_file, b"#!/bin/sh\n# OPENBOT_SHIM\nexit 0\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&proot_file).unwrap().permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&proot_file, perms).unwrap();
        }
        let rootfs = temp.join("rootfs");
        std::fs::create_dir_all(rootfs.join("tmp")).unwrap();
        let ws = temp.join("workspace");
        std::fs::create_dir(&ws).unwrap();
        PRootExecutor::new(PRootConfig::new(&proot_file, &rootfs, &ws))
    }

    #[test]
    fn test_timeout_kills_long_command() {
        let temp = tempdir().unwrap();
        let config = shim_executor(temp.path()).config.clone().with_timeout_secs(1);
        let result = PRootExecutor::new(config).execute("sleep 30").unwrap();
        assert!(result.timed_out);
        assert_ne!(result.exit_code, 0);
        assert!(result.stderr.contains("timed out"));
    }

    #[test]
    fn test_output_is_capped_and_flagged() {
        let temp = tempdir().unwrap();
        let config = shim_executor(temp.path())
            .config
            .clone()
            .with_max_output_bytes(1024)
            .with_timeout_secs(30);
        let result = PRootExecutor::new(config)
            .execute("sh -c 'yes x | head -c 50000'")
            .unwrap();
        assert!(result.stdout_truncated);
        assert!(result.stdout.len() <= 1024);
    }

    #[test]
    fn test_output_is_captured_without_truncation() {
        let temp = tempdir().unwrap();
        let config = shim_executor(temp.path()).config.clone().with_timeout_secs(30);
        let result = PRootExecutor::new(config)
            .execute("printf 'small-output'")
            .unwrap();
        assert_eq!(result.exit_code, 0);
        assert_eq!(result.stdout, "small-output");
        assert!(!result.stdout_truncated);
        assert!(!result.stderr_truncated);
        assert!(!result.timed_out);
    }

    #[test]
    fn test_timeout_does_not_hang_on_background_child() {
        let temp = tempdir().unwrap();
        let config = shim_executor(temp.path()).config.clone().with_timeout_secs(1);
        let started = std::time::Instant::now();
        let result = PRootExecutor::new(config)
            .execute("sh -c 'sleep 30 & wait'")
            .unwrap();
        assert!(result.timed_out);
        // The detached background process must not keep execution blocked.
        assert!(
            started.elapsed() < Duration::from_secs(8),
            "timeout path took {:?}",
            started.elapsed()
        );
    }
}
