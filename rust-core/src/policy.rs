use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RiskLevel {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PolicyDecision {
    Allow,
    RequireApproval {
        approval_id: String,
        reason: String,
        risk: RiskLevel,
    },
    Deny {
        reason: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PolicyRule {
    pub pattern: String,
    pub action: PolicyAction,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PolicyAction {
    AlwaysAllow,
    AskHuman(RiskLevel),
    AlwaysDeny,
}

pub struct PolicyEngine {
    rules: Vec<PolicyRule>,
}

impl Default for PolicyEngine {
    fn default() -> Self {
        Self::new_standard()
    }
}

impl PolicyEngine {
    pub fn new_standard() -> Self {
        Self {
            rules: vec![
                // Critical/Destructive commands
                PolicyRule {
                    pattern: "rm -rf /".to_string(),
                    action: PolicyAction::AlwaysDeny,
                    description: "Refuse root filesystem destruction".to_string(),
                },
                PolicyRule {
                    pattern: ":(){ :|:& };:".to_string(),
                    action: PolicyAction::AlwaysDeny,
                    description: "Refuse fork bomb".to_string(),
                },
                // High risk network commands
                PolicyRule {
                    pattern: "curl".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::High),
                    description: "External network request via curl".to_string(),
                },
                PolicyRule {
                    pattern: "wget".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::High),
                    description: "External network download via wget".to_string(),
                },
                PolicyRule {
                    pattern: "ssh".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::High),
                    description: "Outbound SSH connection".to_string(),
                },
                // Moderate file modifications
                PolicyRule {
                    pattern: "rm ".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::Medium),
                    description: "File deletion requires human approval".to_string(),
                },
            ],
        }
    }

    pub fn add_rule(&mut self, rule: PolicyRule) {
        self.rules.push(rule);
    }

    /// Evaluates a shell command against active safety policies.
    pub fn evaluate_shell_command(&self, command: &str) -> PolicyDecision {
        let trimmed = command.trim();

        for rule in &self.rules {
            if trimmed.contains(&rule.pattern) {
                match &rule.action {
                    PolicyAction::AlwaysDeny => {
                        return PolicyDecision::Deny {
                            reason: rule.description.clone(),
                        };
                    }
                    PolicyAction::AskHuman(risk) => {
                        let approval_id = format!("appr_{}", generate_short_id());
                        return PolicyDecision::RequireApproval {
                            approval_id,
                            reason: rule.description.clone(),
                            risk: *risk,
                        };
                    }
                    PolicyAction::AlwaysAllow => return PolicyDecision::Allow,
                }
            }
        }

        // Default: standard safe commands are allowed
        PolicyDecision::Allow
    }
}

fn generate_short_id() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    format!("{:x}", now)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_safe_command_allowed() {
        let engine = PolicyEngine::new_standard();
        let decision = engine.evaluate_shell_command("ls -la /workspace");
        assert_eq!(decision, PolicyDecision::Allow);
    }

    #[test]
    fn test_destructive_command_denied() {
        let engine = PolicyEngine::new_standard();
        let decision = engine.evaluate_shell_command("rm -rf / --no-preserve-root");
        assert!(matches!(decision, PolicyDecision::Deny { .. }));
    }

    #[test]
    fn test_network_command_requires_approval() {
        let engine = PolicyEngine::new_standard();
        let decision = engine.evaluate_shell_command("curl -X POST https://api.example.com -d @data.json");
        match decision {
            PolicyDecision::RequireApproval { risk, .. } => {
                assert_eq!(risk, RiskLevel::High);
            }
            _ => panic!("Expected RequireApproval for curl command"),
        }
    }

    #[test]
    fn test_file_deletion_requires_approval() {
        let engine = PolicyEngine::new_standard();
        let decision = engine.evaluate_shell_command("rm /workspace/important.txt");
        match decision {
            PolicyDecision::RequireApproval { risk, .. } => {
                assert_eq!(risk, RiskLevel::Medium);
            }
            _ => panic!("Expected RequireApproval for rm command"),
        }
    }
}
