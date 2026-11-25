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
                    pattern: "mkfs".to_string(),
                    action: PolicyAction::AlwaysDeny,
                    description: "Refuse filesystem formatting".to_string(),
                },
                PolicyRule {
                    pattern: ":(){ :|:& };:".to_string(),
                    action: PolicyAction::AlwaysDeny,
                    description: "Refuse fork bomb".to_string(),
                },
                // High risk network/system commands
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
                PolicyRule {
                    pattern: "nc ".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::High),
                    description: "Network socket tool requires approval".to_string(),
                },
                PolicyRule {
                    pattern: "chmod 777".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::High),
                    description: "Overly permissive chmod requires approval".to_string(),
                },
                // Moderate file modifications
                PolicyRule {
                    pattern: "rm ".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::Medium),
                    description: "File deletion requires human approval".to_string(),
                },
                PolicyRule {
                    pattern: "mv ".to_string(),
                    action: PolicyAction::AskHuman(RiskLevel::Medium),
                    description: "Moving/renaming files requires approval".to_string(),
                },
            ],
        }
    }

    pub fn add_rule(&mut self, rule: PolicyRule) {
        self.rules.push(rule);
    }

    /// Evaluates a shell command against active safety policies.
    /// Deny rules take precedence over approval rules.
    pub fn evaluate_shell_command(&self, command: &str) -> PolicyDecision {
        let trimmed = command.trim();
        let mut approval: Option<(String, RiskLevel)> = None;

        for rule in &self.rules {
            if pattern_matches(trimmed, &rule.pattern) {
                match &rule.action {
                    PolicyAction::AlwaysDeny => {
                        return PolicyDecision::Deny {
                            reason: rule.description.clone(),
                        };
                    }
                    PolicyAction::AskHuman(risk) => {
                        if approval.is_none() {
                            approval = Some((rule.description.clone(), *risk));
                        }
                    }
                    PolicyAction::AlwaysAllow => return PolicyDecision::Allow,
                }
            }
        }

        if let Some((reason, risk)) = approval {
            return PolicyDecision::RequireApproval {
                approval_id: format!("appr_{}", generate_short_id()),
                reason,
                risk,
            };
        }

        PolicyDecision::Allow
    }
}

/// Match a policy pattern against a shell command using token-aware boundaries.
///
/// This avoids naive substring false positives (e.g. "curl" matching "scurl")
/// while still catching patterns in common shell contexts (pipes, separators,
/// path prefixes, quotes, substitutions).
fn pattern_matches(command: &str, pattern: &str) -> bool {
    if pattern.is_empty() {
        return false;
    }

    let cmd_lower = command.to_ascii_lowercase();
    let pat_lower = pattern.to_ascii_lowercase();
    let cmd_bytes = cmd_lower.as_bytes();
    let pat_bytes = pat_lower.as_bytes();
    let pat_len = pat_bytes.len();

    let mut search_from = 0;
    while search_from + pat_len <= cmd_bytes.len() {
        if let Some(pos) = cmd_lower[search_from..].find(&pat_lower) {
            let start = search_from + pos;
            let end = start + pat_len;

            let before_ok = if start == 0 {
                true
            } else {
                !is_word_char(command.as_bytes()[start - 1] as char)
            };

            let after_ok = if end >= cmd_bytes.len() {
                true
            } else {
                let after = command.as_bytes()[end] as char;
                let pat_last = pattern.as_bytes()[pat_len - 1] as char;
                // If pattern ends with a word char, require a word boundary after.
                // If pattern ends with whitespace/punctuation (e.g. "rm ", "nc "),
                // the pattern itself already constrains the boundary.
                if is_word_char(pat_last) {
                    !is_word_char(after)
                } else {
                    true
                }
            };

            if before_ok && after_ok {
                return true;
            }

            search_from = start + 1;
        } else {
            break;
        }
    }

    false
}

fn is_word_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

fn generate_short_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let seq = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:x}-{:x}", now, seq)
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
        let decision =
            engine.evaluate_shell_command("curl -X POST https://api.example.com -d @data.json");
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

    #[test]
    fn test_deny_takes_precedence_over_approval() {
        let engine = PolicyEngine::new_standard();
        // contains both "curl" (approval) and "rm -rf /" (deny)
        let decision = engine.evaluate_shell_command("curl http://x && rm -rf /");
        assert!(matches!(decision, PolicyDecision::Deny { .. }));
    }

    // --- regression: pattern matching edge cases ---

    #[test]
    fn test_pattern_matches_substring_bypass_resisted() {
        // "scurl" should NOT match the "curl" rule; "curl" as a path component should match
        assert!(!pattern_matches("scurl foo", "curl"));
        assert!(pattern_matches("/usr/bin/curl foo", "curl"));
        assert!(pattern_matches("curl -V", "curl"));
    }

    #[test]
    fn test_pattern_matches_case_insensitive() {
        assert!(pattern_matches("CURL foo", "curl"));
        assert!(pattern_matches("WGET foo", "wget"));
        assert!(!pattern_matches("curlfoo", "curl"));
    }

    #[test]
    fn test_pattern_matches_trailing_space_patterns() {
        // Pattern "nc " (with trailing space) should match "nc -zv host 8080"
        assert!(pattern_matches("nc -zv host 8080", "nc "));
        // Pattern "nc " should NOT match "ncat ..." (different command)
        assert!(!pattern_matches("ncat -zv host 8080", "nc "));
        // Pattern "rm " should match "rm /tmp/x"
        assert!(pattern_matches("rm /tmp/x", "rm "));
        // "firmware" should NOT match "rm " (word-char boundary before 'r')
        assert!(!pattern_matches("firmware update", "rm "));
    }

    #[test]
    fn test_pattern_matches_rm_rf_slash() {
        // "rm -rf /" must always deny
        assert!(pattern_matches("rm -rf /", "rm -rf /"));
        // "rm -rf /somepath" should also deny (the pattern is at start)
        assert!(pattern_matches("rm -rf /somepath", "rm -rf /"));
    }

    #[test]
    fn test_pattern_matches_fork_bomb() {
        // The fork-bomb pattern must match as a standalone/embedded command
        assert!(pattern_matches(":(){ :|:& };:", ":(){ :|:& };:"));
        // Embedded in a larger command should still match (deny is safer)
        assert!(pattern_matches("echo hi; :(){ :|:& };:", ":(){ :|:& };:"));
    }

    #[test]
    fn test_evaluate_pattern_edge_cases() {
        let engine = PolicyEngine::new_standard();

        // scurl should bypass curl approval
        let decision = engine.evaluate_shell_command("scurl -x https://x");
        assert_eq!(decision, PolicyDecision::Allow);

        // /usr/bin/curl should require approval
        let decision = engine.evaluate_shell_command("/usr/bin/curl -x https://x");
        match decision {
            PolicyDecision::RequireApproval { .. } => {}
            _ => panic!("Expected RequireApproval for /usr/bin/curl"),
        }

        // nc without trailing space after nc but with args should still match pattern "nc "
        // since nc is followed by a space in "nc -zv"
        let decision = engine.evaluate_shell_command("nc -zv somehost 8080");
        match decision {
            PolicyDecision::RequireApproval { .. } => {}
            _ => panic!("Expected RequireApproval for nc command with args"),
        }

        // rm without trailing space after rm but with path should match "rm "
        let decision = engine.evaluate_shell_command("rm /important.txt");
        match decision {
            PolicyDecision::RequireApproval { risk, .. } => {
                assert_eq!(risk, RiskLevel::Medium);
            }
            _ => panic!("Expected RequireApproval for rm with path"),
        }

        // CURL uppercase should still require approval
        let decision = engine.evaluate_shell_command("CURL -x https://x");
        match decision {
            PolicyDecision::RequireApproval { risk, .. } => {
                assert_eq!(risk, RiskLevel::High);
            }
            _ => panic!("Expected RequireApproval for uppercase CURL"),
        }
    }
}
