use crate::policy::{PolicyDecision, PolicyEngine};
use crate::proot::{ExecutionResult, PRootConfig, PRootExecutor, SandboxError};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
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
    policy: PolicyEngine,
    pending_approvals: Arc<Mutex<HashMap<String, PendingApproval>>>,
}

impl OpenBotEngine {
    pub fn new(proot_bin: &str, rootfs_dir: &str, workspace_dir: &str) -> Self {
        let config = PRootConfig::new(proot_bin, rootfs_dir, workspace_dir);
        Self {
            executor: PRootExecutor::new(config),
            policy: PolicyEngine::new_standard(),
            pending_approvals: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Evaluates policy and conditionally executes a shell command or pauses for approval.
    pub fn request_execute(&self, command: &str) -> Result<ActionOutcome, EngineError> {
        let decision = self.policy.evaluate_shell_command(command);

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
}
