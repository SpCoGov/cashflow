//! 月度统计（按类别/子类）与导出 Excel。

use crate::ledger::Entry;
use rust_xlsxwriter::{Format, Workbook, Worksheet, XlsxError};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Stat {
    pub category: String,
    /// 类别级统计为 None
    pub sub: Option<String>,
    pub count: i64,
    /// 以下金额单位均为分
    pub income: i64,
    /// 支出累计（负数）
    pub expense: i64,
    pub net: i64,
    pub expense_count: i64,
    /// 最大一笔支出（最负的值，无支出为 0）
    pub max_expense: i64,
    /// 平均支出（负数，四舍五入到分）
    pub avg_expense: i64,
    /// 类别：占全部支出；子类：占所属类别支出
    pub expense_share: f64,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Analysis {
    pub total_income: i64,
    pub total_expense_abs: i64,
    pub net: i64,
    pub count: i64,
    /// 按时间顺序首次出现的先后排列
    pub categories: Vec<Stat>,
    /// 先按类别、再按子类首次出现的先后排列
    pub subs: Vec<Stat>,
}

impl Stat {
    fn add(&mut self, amount: i64) {
        self.count += 1;
        self.net += amount;
        if amount > 0 {
            self.income += amount;
        } else {
            self.expense += amount;
            self.expense_count += 1;
            self.max_expense = self.max_expense.min(amount);
        }
    }

    fn finish(&mut self, total_expense_abs: i64) {
        self.expense_share = if total_expense_abs == 0 {
            0.0
        } else {
            self.expense.unsigned_abs() as f64 / total_expense_abs as f64
        };
        self.avg_expense = avg_half_up(self.expense, self.expense_count);
    }
}

/// 与 BigDecimal HALF_UP 一致：.5 远离零进位。
fn avg_half_up(sum: i64, n: i64) -> i64 {
    if n == 0 {
        return 0;
    }
    let (q, r) = (sum / n, sum % n);
    if 2 * r.unsigned_abs() >= n.unsigned_abs() {
        q + sum.signum()
    } else {
        q
    }
}

pub fn analyze(mut entries: Vec<Entry>) -> Analysis {
    entries.sort_by_key(|e| e.ts);
    let mut a = Analysis::default();
    let mut cat_idx: HashMap<String, usize> = HashMap::new();
    let mut subs: Vec<Vec<Stat>> = Vec::new();
    for e in &entries {
        let ci = *cat_idx.entry(e.category.clone()).or_insert_with(|| {
            a.categories.push(Stat {
                category: e.category.clone(),
                ..Default::default()
            });
            subs.push(Vec::new());
            a.categories.len() - 1
        });
        a.categories[ci].add(e.amount);
        let list = &mut subs[ci];
        let si = match list
            .iter()
            .position(|s| s.sub.as_deref() == Some(e.sub.as_str()))
        {
            Some(i) => i,
            None => {
                list.push(Stat {
                    category: e.category.clone(),
                    sub: Some(e.sub.clone()),
                    ..Default::default()
                });
                list.len() - 1
            }
        };
        list[si].add(e.amount);

        if e.amount > 0 {
            a.total_income += e.amount;
        } else {
            a.total_expense_abs += -e.amount;
        }
        a.net += e.amount;
        a.count += 1;
    }
    for c in &mut a.categories {
        c.finish(a.total_expense_abs);
    }
    for list in &mut subs {
        let cat_abs: i64 = list.iter().map(|s| -s.expense).sum();
        list.iter_mut().for_each(|s| s.finish(cat_abs));
    }
    a.subs = subs.into_iter().flatten().collect();
    a
}

pub fn export_xlsx(path: &std::path::Path, a: &Analysis) -> Result<(), String> {
    build_workbook(a)
        .and_then(|mut wb| wb.save(path))
        .map_err(|e| format!("导出失败：{e}"))
}

fn yuan(cents: i64) -> f64 {
    cents as f64 / 100.0
}

fn build_workbook(a: &Analysis) -> Result<Workbook, XlsxError> {
    let hdr = Format::new().set_bold();
    let int = Format::new().set_num_format("0");
    let money = Format::new().set_num_format("¥#,##0.00;[Red]-¥#,##0.00");
    let pct = Format::new().set_num_format("0.00%");
    let mut wb = Workbook::new();

    let s = wb.add_worksheet().set_name("概览")?;
    let kv = [
        ("总收入（元）", yuan(a.total_income), &money),
        ("总支出（元）", yuan(-a.total_expense_abs), &money),
        ("净额（元）", yuan(a.net), &money),
        ("记录数", a.count as f64, &int),
    ];
    for (r, (k, v, f)) in kv.into_iter().enumerate() {
        s.write_string_with_format(r as u32, 0, k, &hdr)?;
        s.write_number_with_format(r as u32, 1, v, f)?;
    }
    s.autofit();

    let write_stats = |s: &mut Worksheet,
                       headers: &[&str],
                       stats: &[Stat],
                       with_sub: bool|
     -> Result<(), XlsxError> {
        for (c, h) in headers.iter().enumerate() {
            s.write_string_with_format(0, c as u16, *h, &hdr)?;
        }
        for (i, st) in stats.iter().enumerate() {
            let r = i as u32 + 1;
            let mut c = 0u16;
            let mut next = || {
                c += 1;
                c - 1
            };
            s.write_string(r, next(), &st.category)?;
            if with_sub {
                s.write_string(r, next(), st.sub.as_deref().unwrap_or(""))?;
            }
            s.write_number_with_format(r, next(), st.count as f64, &int)?;
            for v in [st.income, st.expense, st.net] {
                s.write_number_with_format(r, next(), yuan(v), &money)?;
            }
            s.write_number_with_format(r, next(), st.expense_share, &pct)?;
            for v in [st.avg_expense, st.max_expense] {
                s.write_number_with_format(r, next(), yuan(v), &money)?;
            }
        }
        s.autofit();
        Ok(())
    };
    let s = wb.add_worksheet().set_name("类别明细")?;
    let cat_headers = [
        "类别",
        "笔数",
        "收入(元)",
        "支出(元)",
        "净额(元)",
        "支出占比(全局)",
        "平均支出(元)",
        "最大支出(元)",
    ];
    write_stats(s, &cat_headers, &a.categories, false)?;
    let s = wb.add_worksheet().set_name("子类明细")?;
    let sub_headers = [
        "类别",
        "子类别",
        "笔数",
        "收入(元)",
        "支出(元)",
        "净额(元)",
        "在本类别占比",
        "平均支出(元)",
        "最大支出(元)",
    ];
    write_stats(s, &sub_headers, &a.subs, true)?;
    Ok(wb)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn e(ts: i64, amount: i64, category: &str, sub: &str) -> Entry {
        Entry {
            ts,
            amount,
            category: category.into(),
            sub: sub.into(),
            note: String::new(),
        }
    }

    #[test]
    fn computes_category_and_sub_stats() {
        let a = analyze(vec![
            e(3, -500, "餐饮", "小吃"),
            e(1, -1000, "餐饮", "正餐"),
            e(2, 150000, "工资", "固定"),
            e(4, -501, "餐饮", "正餐"),
            e(5, -2000, "交通", "打车"),
        ]);
        assert_eq!(
            (a.total_income, a.total_expense_abs, a.net, a.count),
            (150000, 4001, 145999, 5)
        );
        let names: Vec<&str> = a.categories.iter().map(|s| s.category.as_str()).collect();
        assert_eq!(names, ["餐饮", "工资", "交通"]);

        let food = &a.categories[0];
        assert_eq!(
            (food.count, food.expense, food.expense_count),
            (3, -2001, 3)
        );
        assert_eq!((food.max_expense, food.avg_expense), (-1000, -667));
        assert!((food.expense_share - 2001.0 / 4001.0).abs() < 1e-12);
        let salary = &a.categories[1];
        assert_eq!(
            (
                salary.income,
                salary.max_expense,
                salary.avg_expense,
                salary.expense_share
            ),
            (150000, 0, 0, 0.0)
        );

        let subs: Vec<(&str, &str)> = a
            .subs
            .iter()
            .map(|s| (s.category.as_str(), s.sub.as_deref().unwrap()))
            .collect();
        assert_eq!(
            subs,
            [
                ("餐饮", "正餐"),
                ("餐饮", "小吃"),
                ("工资", "固定"),
                ("交通", "打车")
            ]
        );
        // -1501 / 2 = -750.5 → HALF_UP 远离零 → -751
        assert_eq!(a.subs[0].avg_expense, -751);
        assert!((a.subs[0].expense_share - 1501.0 / 2001.0).abs() < 1e-12);
    }

    #[test]
    fn half_up_rounds_away_from_zero() {
        assert_eq!(avg_half_up(-5, 2), -3);
        assert_eq!(avg_half_up(5, 2), 3);
        assert_eq!(avg_half_up(-4, 3), -1);
        assert_eq!(avg_half_up(0, 0), 0);
    }

    #[test]
    fn exports_three_sheets() {
        use calamine::{Reader, Xlsx, open_workbook};
        let a = analyze(vec![e(1, -1234, "餐饮", "正餐"), e(2, 500, "红包", "收到")]);
        let path =
            std::env::temp_dir().join(format!("cashflow-analysis-{}.xlsx", std::process::id()));
        export_xlsx(&path, &a).unwrap();
        let mut wb: Xlsx<_> = open_workbook(&path).unwrap();
        assert_eq!(wb.sheet_names(), ["概览", "类别明细", "子类明细"]);
        let sub = wb.worksheet_range("子类明细").unwrap();
        assert_eq!(sub.get_value((1, 4)), Some(&calamine::Data::Float(-12.34)));
        std::fs::remove_file(&path).unwrap();
    }
}
