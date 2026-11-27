use crate::policy::{PolicyDecision, PolicyEngine, PolicyRule};
use crate::proot::{ExecutionResult, PRootConfig, PRootExecutor, SandboxError};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex};
use thiserror::Error;

#[derive(Error, Debug, PartialEq)]
pub enum EngineError {
    #[error("Sandbox error: {0}")]
    Sandbox(#[from] SandboxError),
    #[error("Approval not found with id: {0}")]
    ApprovalNotFound(String),
    #[error("Action was denied by policy: {0}")]
    ActionDenied(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PendingApproval {
    pub id: String,
    pub command: String,
    pub reason: String,
    pub risk_description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ActionOutcome {
    Executed(ExecutionResult),
    NeedsApproval(PendingApproval),
    Denied { reason: String },
}

pub struct OpenBotEngine {
    executor: PRootExecutor,
    policy: Mutex<PolicyEngine>,
    pending_approvals: Arc<Mutex<HashMap<String, PendingApproval>>>,
}

impl OpenBotEngine {
    pub fn new(proot_bin: &str, rootfs_dir: &str, workspace_dir: &str) -> Self {
        let config = Self::config_for(proot_bin, rootfs_dir, workspace_dir);
        Self {
            executor: PRootExecutor::new(config),
            policy: Mutex::new(PolicyEngine::new_standard()),
            pending_approvals: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Replaces user-defined policy rules while preserving the standard baseline rules.
    pub fn set_custom_policy_rules(&self, custom_rules: Vec<PolicyRule>) {
        let mut engine = PolicyEngine::new_standard();
        for rule in custom_rules {
            if !rule.pattern.trim().is_empty() {
                engine.add_rule(rule);
            }
        }
        match self.policy.lock() {
            Ok(mut guard) => *guard = engine,
            Err(poisoned) => *poisoned.into_inner() = engine,
        }
    }

    /// Builds the sandbox config, choosing a shell that actually exists.
    ///
    /// A bundled rootfs supplies `/bin/sh`; the Android minimal rootfs instead
    /// relies on `/system/bin/sh` through the `/system` bind mount.
    fn config_for(proot_bin: &str, rootfs_dir: &str, workspace_dir: &str) -> PRootConfig {
        let mut config = PRootConfig::new(proot_bin, rootfs_dir, workspace_dir);
        let rootfs_shell = config.rootfs_path.join("bin/sh");
        // Use symlink_metadata so an in-rootfs absolute symlink (e.g. Alpine's
        // /bin/sh -> /bin/busybox) is recognized even when /bin/busybox is absent on host.
        if rootfs_shell.symlink_metadata().is_err() && Path::new("/system/bin/sh").exists() {
            config = config.with_shell_path("/system/bin/sh");
        }
        config
    }

    /// `"proot"` for real isolation, `"shim"` for the degraded fallback.
    pub fn sandbox_mode(&self) -> &'static str {
        self.executor.sandbox_mode()
    }

    /// Evaluates policy and conditionally executes a shell command or pauses for approval.
    pub fn request_execute(&self, command: &str) -> Result<ActionOutcome, EngineError> {
        let decision = match self.policy.lock() {
            Ok(guard) => guard.evaluate_shell_command(command),
            Err(poisoned) => poisoned.into_inner().evaluate_shell_command(command),
        };

        match decision {
            PolicyDecision::Allow => {
                let res = self.executor.execute(command)?;
                Ok(ActionOutcome::Executed(res))
            }
            PolicyDecision::RequireApproval {
                approval_id,
                reason,
                risk,
            } => {
                let pending = PendingApproval {
                    id: approval_id.clone(),
                    command: command.to_string(),
                    reason,
                    risk_description: format!("{:?}", risk),
                };

                let mut lock = self.pending_approvals.lock().unwrap();
                lock.insert(approval_id, pending.clone());

                Ok(ActionOutcome::NeedsApproval(pending))
            }
            PolicyDecision::Deny { reason } => Ok(ActionOutcome::Denied { reason }),
        }
    }

    /// Resolves a pending human approval. If approved, executes the paused action.
    pub fn resolve_approval(
        &self,
        approval_id: &str,
        approved: bool,
    ) -> Result<ExecutionResult, EngineError> {
        let pending = {
            let mut lock = self.pending_approvals.lock().unwrap();
            lock.remove(approval_id)
                .ok_or_else(|| EngineError::ApprovalNotFound(approval_id.to_string()))?
        };

        if !approved {
            return Err(EngineError::ActionDenied(format!(
                "Human reviewer rejected action: {}",
                pending.command
            )));
        }

        let res = self.executor.execute(&pending.command)?;
        Ok(res)
    }

    /// Returns list of all currently pending approvals.
    pub fn list_pending_approvals(&self) -> Vec<PendingApproval> {
        let lock = self.pending_approvals.lock().unwrap();
        lock.values().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn setup_mock_engine() -> (tempfile::TempDir, OpenBotEngine) {
        let temp = tempdir().unwrap();
        let proot = temp.path().join("proot");
        // Create dummy script that echoes args
        std::fs::write(&proot, b"#!/bin/sh\necho \"MOCK OUTPUT: $@\"\n").unwrap();
        
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&proot).unwrap().permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&proot, perms).unwrap();
        }

        let rootfs = temp.path().join("rootfs");
        std::fs::create_dir(&rootfs).unwrap();
        let ws = temp.path().join("workspace");
        std::fs::create_dir(&ws).unwrap();

        let engine = OpenBotEngine::new(
            proot.to_str().unwrap(),
            rootfs.to_str().unwrap(),
            ws.to_str().unwrap(),
        );

        (temp, engine)
    }

    #[test]
    fn test_engine_safe_command_execution() {
        let (_temp, engine) = setup_mock_engine();
        let outcome = engine.request_execute("ls -la").unwrap();
        match outcome {
            ActionOutcome::Executed(res) => {
                assert!(res.stdout.contains("MOCK OUTPUT:"));
            }
            _ => panic!("Expected safe command to be executed immediately"),
        }
    }

    #[test]
    fn test_engine_approval_flow() {
        let (_temp, engine) = setup_mock_engine();
        // Request a network command that triggers approval
        let outcome = engine
            .request_execute("curl -s https://example.com/api")
            .unwrap();

        let pending_id = match outcome {
            ActionOutcome::NeedsApproval(pending) => {
                assert_eq!(pending.risk_description, "High");
                assert_eq!(engine.list_pending_approvals().len(), 1);
                pending.id
            }
            _ => panic!("Expected curl to require approval"),
        };

        // Approve it
        let exec_result = engine.resolve_approval(&pending_id, true).unwrap();
        assert!(exec_result.stdout.contains("MOCK OUTPUT:"));
        assert_eq!(engine.list_pending_approvals().len(), 0);
    }

    #[test]
    fn test_engine_rejection_flow() {
        let (_temp, engine) = setup_mock_engine();
        let outcome = engine.request_execute("rm /workspace/file.txt").unwrap();

        let pending_id = match outcome {
            ActionOutcome::NeedsApproval(p) => p.id,
            _ => panic!("Expected rm to require approval"),
        };

        let result = engine.resolve_approval(&pending_id, false);
        assert!(matches!(result, Err(EngineError::ActionDenied(_))));
        assert_eq!(engine.list_pending_approvals().len(), 0);
    }

    #[test]
    fn test_engine_is_shareable_across_threads() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<OpenBotEngine>();
    }

    #[test]
    fn test_custom_policy_rules_applied_without_weakening_baseline() {
        let (_temp, engine) = setup_mock_engine();
        engine.set_custom_policy_rules(vec![
            PolicyRule {
                pattern: "git push".to_string(),
                action: crate::policy::PolicyAction::AlwaysDeny,
                description: "Block git push".to_string(),
            },
            PolicyRule {
                pattern: "apk add".to_string(),
                action: crate::policy::PolicyAction::AskHuman(crate::policy::RiskLevel::High),
                description: "Approve package install".to_string(),
            },
        ]);

        let denied = engine.request_execute("git push origin main").unwrap();
        assert!(matches!(denied, ActionOutcome::Denied { .. }));

        let baseline_denied = engine.request_execute("rm -rf /").unwrap();
        assert!(matches!(baseline_denied, ActionOutcome::Denied { .. }));

        let approval = engine.request_execute("apk add python3").unwrap();
        assert!(matches!(approval, ActionOutcome::NeedsApproval(_)));
    }

    #[test]
    fn test_pending_approvals_readable_during_execution() {
        let temp = tempdir().unwrap();
        let (temp_keep, engine) = {
            let proot = temp.path().join("proot");
            std::fs::write(&proot, b"#!/bin/sh\n# OPENBOT_SHIM\nexit 0\n").unwrap();
            let rootfs = temp.path().join("rootfs");
            std::fs::create_dir_all(rootfs.join("tmp")).unwrap();
            let ws = temp.path().join("workspace");
            std::fs::create_dir(&ws).unwrap();
            let engine = OpenBotEngine::new(
                proot.to_str().unwrap(),
                rootfs.to_str().unwrap(),
                ws.to_str().unwrap(),
            );
            (temp, engine)
        };
        let engine = Arc::new(engine);
        let worker = {
            let engine = Arc::clone(&engine);
            std::thread::spawn(move || engine.request_execute("sleep 2"))
        };

        std::thread::sleep(std::time::Duration::from_millis(150));
        let started = std::time::Instant::now();
        let pending = engine.list_pending_approvals();
        assert!(pending.is_empty());
        assert!(
            started.elapsed() < std::time::Duration::from_secs(1),
            "listing approvals blocked on a running command"
        );

        let outcome = worker.join().unwrap().unwrap();
        assert!(matches!(outcome, ActionOutcome::Executed(_)));
        drop(temp_keep);
    }
}
