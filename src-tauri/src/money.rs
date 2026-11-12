/// 把元的十进制文本（如 `12.5`、`-0.05`、`+3.`）精确换算成分；超过两位有效小数时返回 None，
/// 对应 Java `new BigDecimal(s).setScale(2)` 在需要舍入时抛异常的行为。
pub fn parse_cents_exact(s: &str) -> Option<i64> {
    let s = s.trim();
    let (neg, body) = match s.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, s.strip_prefix('+').unwrap_or(s)),
    };
    let (int, frac) = body.split_once('.').unwrap_or((body, ""));
    let digits = |p: &str| p.bytes().all(|b| b.is_ascii_digit());
    if (int.is_empty() && frac.is_empty()) || !digits(int) || !digits(frac) {
        return None;
    }
    let frac = frac.trim_end_matches('0');
    if frac.len() > 2 {
        return None;
    }
    let int_v: i64 = if int.is_empty() { 0 } else { int.parse().ok()? };
    let frac_v: i64 = format!("{frac:0<2}").parse().ok()?;
    let v = int_v.checked_mul(100)?.checked_add(frac_v)?;
    Some(if neg { -v } else { v })
}

#[cfg(test)]
mod tests {
    use super::parse_cents_exact as p;

    #[test]
    fn parses_exact_amounts_only() {
        assert_eq!(p("12.5"), Some(1250));
        assert_eq!(p(" -0.05 "), Some(-5));
        assert_eq!(p("+3."), Some(300));
        assert_eq!(p(".5"), Some(50));
        assert_eq!(p("1.2300"), Some(123));
        assert_eq!(p("0"), Some(0));
        assert_eq!(p("1.234"), None);
        assert_eq!(p(""), None);
        assert_eq!(p("."), None);
        assert_eq!(p("1,000"), None);
        assert_eq!(p("--1"), None);
        assert_eq!(p("99999999999999999999"), None);
    }
}
