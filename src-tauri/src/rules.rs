//! 导入规则：YAML 配置（与 Java 版字段一致）+ 顺序匹配的规则引擎。

use crate::ledger::Entry;
use regex::Regex;
use serde::{Deserialize, Serialize};

/// 解析器产出的统一交易（尚未归类）。
#[derive(Clone, Debug, PartialEq)]
pub struct RawTxn {
    pub ts: i64,
    pub amount: i64,
    pub payee: String,
    pub item: String,
    pub note: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct RuleConfig {
    pub version: i32,
    #[serde(skip_serializing_if = "Defaults::is_empty")]
    pub defaults: Defaults,
    pub rules: Vec<RuleDef>,
}

impl Default for RuleConfig {
    fn default() -> Self {
        Self {
            version: 1,
            defaults: Defaults::default(),
            rules: Vec::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Defaults {
    #[serde(skip_serializing_if = "is_blank")]
    pub category: String,
    #[serde(skip_serializing_if = "is_blank")]
    pub sub: String,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub note_fallback_order: Vec<String>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub only_append_from_last_date: bool,
}

impl Default for Defaults {
    fn default() -> Self {
        Self {
            category: String::new(),
            sub: String::new(),
            note_fallback_order: vec!["note".into(), "item".into(), "payee".into()],
            only_append_from_last_date: false,
        }
    }
}

impl Defaults {
    fn is_empty(&self) -> bool {
        is_blank(&self.category)
            && is_blank(&self.sub)
            && self.note_fallback_order.is_empty()
            && !self.only_append_from_last_date
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct RuleDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "When::is_empty")]
    pub when: When,
    #[serde(skip_serializing_if = "Then::is_empty")]
    pub then: Then,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct When {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payee: Option<TextMatch>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item: Option<TextMatch>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<TextMatch>,
    /// 例：">0"、"<=123.45"
    #[serde(skip_serializing_if = "Option::is_none")]
    pub amount: Option<String>,
}

impl When {
    fn is_empty(&self) -> bool {
        self.payee.is_none() && self.item.is_none() && self.note.is_none() && self.amount.is_none()
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Then {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drop: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sub: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note_replace: Option<NoteReplace>,
}

impl Then {
    fn is_empty(&self) -> bool {
        self.drop.is_none()
            && self.category.is_none()
            && self.sub.is_none()
            && self.note_replace.is_none()
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct TextMatch {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub contains: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub equals: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub regex: Option<String>,
    /// 缺省为 true；只作用于 contains/equals（与 Java 版一致，regex 始终区分大小写）
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ignore_case: Option<bool>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct NoteReplace {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub regex: Option<String>,
    /// 替换串使用 Java `replaceAll` 语法（`$1`、`${name}`、`\$`）
    #[serde(skip_serializing_if = "Option::is_none")]
    pub with: Option<String>,
}

fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

fn non_blank(s: Option<String>) -> Option<String> {
    s.filter(|v| !is_blank(v))
}

pub fn load(path: &std::path::Path) -> Result<RuleConfig, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("读取规则失败：{e}"))?;
    if text.trim().is_empty() {
        return Ok(RuleConfig::default());
    }
    serde_yaml::from_str(&text).map_err(|e| format!("规则文件格式错误：{e}"))
}

pub fn save(path: &std::path::Path, cfg: RuleConfig) -> Result<(), String> {
    let text = serde_yaml::to_string(&normalized(cfg)).map_err(|e| e.to_string())?;
    crate::write_atomic(path, text.as_bytes())
}

/// 与 Java 版导出一致：省略空白字段、默认值和空的条件/动作。
fn normalized(mut cfg: RuleConfig) -> RuleConfig {
    let tm = |t: Option<TextMatch>| {
        t.map(|t| TextMatch {
            contains: non_blank(t.contains),
            equals: non_blank(t.equals),
            regex: non_blank(t.regex),
            ignore_case: t.ignore_case.filter(|ic| !ic),
        })
        .filter(|t| t.contains.is_some() || t.equals.is_some() || t.regex.is_some())
    };
    for r in &mut cfg.rules {
        r.name = non_blank(r.name.take());
        r.when.payee = tm(r.when.payee.take());
        r.when.item = tm(r.when.item.take());
        r.when.note = tm(r.when.note.take());
        r.when.amount = non_blank(r.when.amount.take());
        r.then.drop = r.then.drop.filter(|d| *d);
        r.then.category = non_blank(r.then.category.take());
        r.then.sub = non_blank(r.then.sub.take());
        r.then.note_replace = r.then.note_replace.take().and_then(|nr| {
            non_blank(nr.regex).map(|regex| NoteReplace {
                regex: Some(regex),
                with: Some(nr.with.unwrap_or_default()),
            })
        });
    }
    cfg
}

struct CompiledText {
    equals: Option<String>,
    contains: Option<String>,
    regex: Option<Regex>,
    ignore_case: bool,
}

impl CompiledText {
    fn matches(&self, text: &str) -> bool {
        let fold = |s: &str| {
            if self.ignore_case {
                s.to_lowercase()
            } else {
                s.to_string()
            }
        };
        let cmp = fold(text);
        self.equals.as_deref().is_none_or(|t| cmp == fold(t))
            && self
                .contains
                .as_deref()
                .is_none_or(|t| cmp.contains(&fold(t)))
            && self.regex.as_ref().is_none_or(|re| re.is_match(text))
    }
}

struct CompiledRule<'a> {
    payee: Option<CompiledText>,
    item: Option<CompiledText>,
    note: Option<CompiledText>,
    amount: Option<(&'static str, i64)>,
    then: &'a Then,
    note_replace: Option<(Regex, String)>,
}

impl CompiledRule<'_> {
    fn matches(&self, t: &RawTxn) -> bool {
        let text_ok = |m: &Option<CompiledText>, s: &str| m.as_ref().is_none_or(|m| m.matches(s));
        text_ok(&self.payee, &t.payee)
            && text_ok(&self.item, &t.item)
            && text_ok(&self.note, &t.note)
            && self.amount.is_none_or(|(op, rhs)| match op {
                ">" => t.amount > rhs,
                "<" => t.amount < rhs,
                ">=" => t.amount >= rhs,
                "<=" => t.amount <= rhs,
                "==" => t.amount == rhs,
                _ => t.amount != rhs,
            })
    }
}

fn compile_regex(rule: &str, pattern: &str) -> Result<Regex, String> {
    Regex::new(pattern).map_err(|e| format!("规则【{rule}】的正则 /{pattern}/ 无效：{e}"))
}

fn compile(r: &RuleDef) -> Result<CompiledRule<'_>, String> {
    let name = r.name.as_deref().unwrap_or("未命名");
    let text = |m: &Option<TextMatch>| -> Result<Option<CompiledText>, String> {
        let Some(m) = m else { return Ok(None) };
        Ok(Some(CompiledText {
            equals: m.equals.clone(),
            contains: m.contains.clone(),
            regex: m
                .regex
                .as_deref()
                .map(|p| compile_regex(name, p))
                .transpose()?,
            ignore_case: m.ignore_case.unwrap_or(true),
        }))
    };
    let amount = r
        .when
        .amount
        .as_deref()
        .map(|e| parse_amount_expr(e).ok_or_else(|| format!("规则【{name}】的金额表达式无效：{e}")))
        .transpose()?;
    let note_replace = match &r.then.note_replace {
        Some(NoteReplace {
            regex: Some(p),
            with,
        }) if !is_blank(p) => {
            let re = compile_regex(name, p)?;
            let rep = java_replacement(with.as_deref().unwrap_or(""), re.captures_len() - 1);
            Some((re, rep))
        }
        _ => None,
    };
    Ok(CompiledRule {
        payee: text(&r.when.payee)?,
        item: text(&r.when.item)?,
        note: text(&r.when.note)?,
        amount,
        then: &r.then,
        note_replace,
    })
}

/// `>0`、`<=123.45`、`!= 10` 之类；金额最多两位小数。
fn parse_amount_expr(expr: &str) -> Option<(&'static str, i64)> {
    let e: String = expr.chars().filter(|c| *c != ' ').collect();
    let op = [">=", "<=", "==", "!=", ">", "<"]
        .into_iter()
        .find(|op| e.starts_with(op))?;
    Some((op, crate::money::parse_cents_exact(&e[op.len()..])?))
}

/// 把 Java `Matcher.replaceAll` 的替换串转换为 regex crate 语法。
fn java_replacement(with: &str, group_count: usize) -> String {
    let mut out = String::new();
    let literal = |out: &mut String, c: char| {
        if c == '$' {
            out.push_str("$$");
        } else {
            out.push(c);
        }
    };
    let mut chars = with.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                if let Some(n) = chars.next() {
                    literal(&mut out, n);
                }
            }
            '$' if chars.peek() == Some(&'{') => {
                chars.next();
                let name: String = chars.by_ref().take_while(|c| *c != '}').collect();
                out.push_str(&format!("${{{name}}}"));
            }
            '$' if chars.peek().is_some_and(|c| c.is_ascii_digit()) => {
                // Java 先取一位数字，后续数字只在组号仍然存在时才并入。
                let mut num = chars.next().unwrap().to_digit(10).unwrap() as usize;
                while let Some(d) = chars.peek().and_then(|c| c.to_digit(10)) {
                    let next = num * 10 + d as usize;
                    if next > group_count {
                        break;
                    }
                    num = next;
                    chars.next();
                }
                out.push_str(&format!("${{{num}}}"));
            }
            c => literal(&mut out, c),
        }
    }
    out
}

/// 顺序匹配首条命中的规则；命中 drop 的交易丢弃。
pub fn apply(src: &[RawTxn], cfg: &RuleConfig) -> Result<Vec<Entry>, String> {
    let rules = cfg
        .rules
        .iter()
        .map(compile)
        .collect::<Result<Vec<_>, _>>()?;
    let mut out = Vec::with_capacity(src.len());
    for t in src {
        let hit = rules.iter().find(|r| r.matches(t));
        let then = hit.map(|r| r.then);
        if then.and_then(|t| t.drop) == Some(true) {
            continue;
        }
        let pick = |v: Option<&String>, d: &str| {
            v.filter(|s| !is_blank(s))
                .cloned()
                .unwrap_or_else(|| d.to_string())
        };
        let category = pick(
            then.and_then(|t| t.category.as_ref()),
            &cfg.defaults.category,
        );
        let sub = pick(then.and_then(|t| t.sub.as_ref()), &cfg.defaults.sub);

        let mut note = t.note.clone();
        if let Some((re, rep)) = hit.and_then(|r| r.note_replace.as_ref()) {
            note = re.replace_all(&note, rep.as_str()).into_owned();
        }
        if is_blank(&note) {
            note = fallback_note(t, &cfg.defaults.note_fallback_order);
        }
        out.push(Entry {
            ts: t.ts,
            amount: t.amount,
            category,
            sub,
            note,
        });
    }
    Ok(out)
}

fn fallback_note(t: &RawTxn, order: &[String]) -> String {
    order
        .iter()
        .filter_map(|k| match k.as_str() {
            "note" => Some(&t.note),
            "item" => Some(&t.item),
            "payee" => Some(&t.payee),
            _ => None,
        })
        .find(|v| !is_blank(v))
        .cloned()
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn txn(amount: i64, payee: &str, item: &str, note: &str) -> RawTxn {
        RawTxn {
            ts: 1,
            amount,
            payee: payee.into(),
            item: item.into(),
            note: note.into(),
        }
    }

    const YAML: &str = r#"
version: 1
defaults:
  category: 其他
  sub: 未分类
  noteFallbackOrder: [note, item, payee]
rules:
  - name: 丢弃零钱通
    when:
      payee: { contains: 零钱通 }
    then: { drop: true }
  - name: 咖啡
    when:
      payee: { contains: STARBUCKS }
      amount: "<0"
    then:
      category: 饮料
      sub: 咖啡
      noteReplace: { regex: '订单(\d+)', with: '#$1\$' }
  - name: 大额收入
    when:
      amount: ">= 1000.5"
    then: { category: 收入, sub: 其他收入 }
  - name: 区分大小写
    when:
      item: { equals: Taxi, ignoreCase: false }
    then: { category: 交通, sub: 打车 }
"#;

    #[test]
    fn applies_first_matching_rule_with_java_semantics() {
        let cfg: RuleConfig = serde_yaml::from_str(YAML).unwrap();
        let src = [
            txn(-100, "零钱通", "", ""),
            txn(-3200, "Starbucks 星巴克", "拿铁", "订单123"),
            txn(3200, "starbucks", "", ""),
            txn(100050, "公司", "工资", ""),
            txn(100049, "公司", "", ""),
            txn(-900, "", "taxi", "  "),
            txn(-900, "", "Taxi", ""),
        ];
        let out = apply(&src, &cfg).unwrap();
        let got: Vec<(&str, &str, &str)> = out
            .iter()
            .map(|e| (e.category.as_str(), e.sub.as_str(), e.note.as_str()))
            .collect();
        assert_eq!(
            got,
            [
                ("饮料", "咖啡", "#123$"),
                ("其他", "未分类", "starbucks"),
                ("收入", "其他收入", "工资"),
                ("其他", "未分类", "公司"),
                ("其他", "未分类", "taxi"),
                ("交通", "打车", "Taxi"),
            ]
        );
    }

    #[test]
    fn missing_yaml_sections_use_java_defaults() {
        let cfg: RuleConfig = serde_yaml::from_str("rules:\n  - name: x\n").unwrap();
        assert_eq!(cfg.version, 1);
        assert_eq!(cfg.defaults.note_fallback_order, ["note", "item", "payee"]);
        // 无条件的规则匹配一切
        let out = apply(&[txn(-1, "p", "", "")], &cfg).unwrap();
        assert_eq!(out[0].note, "p");
    }

    #[test]
    fn invalid_rules_report_the_rule_name() {
        let mut cfg = RuleConfig::default();
        cfg.rules.push(RuleDef {
            name: Some("坏金额".into()),
            when: When {
                amount: Some(">1.234".into()),
                ..Default::default()
            },
            ..Default::default()
        });
        assert!(apply(&[], &cfg).unwrap_err().contains("坏金额"));
        cfg.rules[0].when.amount = None;
        cfg.rules[0].when.note = Some(TextMatch {
            regex: Some("(".into()),
            ..Default::default()
        });
        assert!(apply(&[], &cfg).unwrap_err().contains("坏金额"));
    }

    #[test]
    fn java_replacement_syntax_is_translated() {
        assert_eq!(java_replacement("$1a", 1), "${1}a");
        assert_eq!(java_replacement("$12", 1), "${1}2");
        assert_eq!(java_replacement("$12", 12), "${12}");
        assert_eq!(java_replacement(r"\$5 ${y}", 0), "$$5 ${y}");
        assert_eq!(java_replacement("a$", 0), "a$$");
    }

    #[test]
    fn save_omits_empty_fields_and_round_trips() {
        let mut cfg: RuleConfig = serde_yaml::from_str(YAML).unwrap();
        cfg.rules.push(RuleDef {
            name: Some("  ".into()),
            when: When {
                payee: Some(TextMatch {
                    contains: Some(" ".into()),
                    ignore_case: Some(true),
                    ..Default::default()
                }),
                ..Default::default()
            },
            then: Then {
                drop: Some(false),
                note_replace: Some(NoteReplace {
                    regex: None,
                    with: Some("x".into()),
                }),
                ..Default::default()
            },
        });
        let text = serde_yaml::to_string(&normalized(cfg.clone())).unwrap();
        assert!(text.contains("rules:\n- name: 丢弃零钱通\n"), "{text}");
        assert!(text.ends_with("- {}\n"), "空规则只保留占位：{text}");
        assert!(!text.contains("ignoreCase: true"));
        assert!(text.contains("ignoreCase: false"));
        let back: RuleConfig = serde_yaml::from_str(&text).unwrap();
        assert_eq!(back.rules[..4], cfg.rules[..4]);
        assert_eq!(back.defaults, cfg.defaults);
    }
}
