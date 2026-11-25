use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use thiserror::Error;

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
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ExecutionResult {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
}

#[derive(Debug, Clone)]
pub struct PRootConfig {
    pub proot_path: PathBuf,
    pub rootfs_path: PathBuf,
    pub workspace_path: PathBuf,
    pub default_working_dir: String,
    pub extra_bindings: Vec<(PathBuf, String)>,
}

impl PRootConfig {
    pub fn new<P: AsRef<Path>>(proot: P, rootfs: P, workspace: P) -> Self {
        Self {
            proot_path: proot.as_ref().to_path_buf(),
            rootfs_path: rootfs.as_ref().to_path_buf(),
            workspace_path: workspace.as_ref().to_path_buf(),
            default_working_dir: "/workspace".to_string(),
            extra_bindings: Vec::new(),
        }
    }

    pub fn with_binding<P: AsRef<Path>>(mut self, host_path: P, guest_path: &str) -> Self {
        self.extra_bindings
            .push((host_path.as_ref().to_path_buf(), guest_path.to_string()));
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
            "-b".to_string(),
            "/dev".to_string(),
            "-b".to_string(),
            "/proc".to_string(),
            "-b".to_string(),
            format!(
                "{}:{}",
                self.config.workspace_path.to_string_lossy(),
                self.config.default_working_dir
            ),
        ];

        for (host, guest) in &self.config.extra_bindings {
            args.push("-b".to_string());
            args.push(format!("{}:{}", host.to_string_lossy(), guest));
        }

        args.push("-w".to_string());
        args.push(self.config.default_working_dir.clone());
        args.push("/bin/sh".to_string());
        args.push("-c".to_string());
        args.push(command_str.to_string());

        args
    }

    /// Detects whether the configured proot binary is a shim/fallback script
    /// rather than a real proot binary. Shims cannot parse proot-style args.
    fn is_shim(&self) -> bool {
        std::fs::read_to_string(&self.config.proot_path)
            .map(|s| s.contains("OPENBOT_SHIM"))
            .unwrap_or(false)
    }

    /// Executes a shell command inside the sandbox.
    /// Uses real proot when available; falls back to direct execution via sh
    /// in workspace dir when the proot path is an OpenBot shim.
    pub fn execute(&self, command_str: &str) -> Result<ExecutionResult, SandboxError> {
        self.validate()?;

        let output = if self.is_shim() {
            // Shim mode: execute directly with /system/bin/sh or sh in workspace.
            // This is a degraded sandbox (no rootfs isolation) but keeps commands working.
            let shell = if Path::new("/system/bin/sh").exists() {
                "/system/bin/sh"
            } else {
                "/bin/sh"
            };
            Command::new(shell)
                .arg("-c")
                .arg(command_str)
                .current_dir(&self.config.workspace_path)
                .env("HOME", self.config.workspace_path.to_string_lossy().as_ref())
                .env("TMPDIR", self.config.rootfs_path.join("tmp").to_string_lossy().as_ref())
                .env("PATH", "/system/bin:/system/xbin:/vendor/bin:/usr/bin:/bin")
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .map_err(|e| SandboxError::ExecutionFailed(e.to_string()))?
        } else {
            let args = self.build_args(command_str);
            Command::new(&self.config.proot_path)
                .args(&args)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .map_err(|e| SandboxError::ExecutionFailed(e.to_string()))?
        };

        Ok(ExecutionResult {
            exit_code: output.status.code().unwrap_or(-1),
            stdout: String::from_utf8_lossy(&output.stdout).to_string(),
            stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        })
    }
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
    }
}
