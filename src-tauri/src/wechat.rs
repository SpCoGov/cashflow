//! 微信支付账单（.xlsx 或 .csv）解析为统一交易。

use crate::rules::RawTxn;
use calamine::{Data, Reader, Xlsx, open_workbook};
use chrono::{Local, NaiveDateTime, TimeZone};
use std::collections::HashMap;
use std::path::Path;

const HEADER: &str = "交易时间";
const TIME_FORMATS: [&str; 3] = ["%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y/%m/%d %H:%M"];

pub fn parse_file(path: &Path) -> Result<Vec<RawTxn>, String> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    match ext.as_str() {
        "xlsx" => parse_xlsx(path),
        "csv" | "txt" => parse_csv(&std::fs::read(path).map_err(|e| format!("读取账单失败：{e}"))?),
        _ => Err(format!(
            "没有可用的解析器：{}",
            path.file_name().unwrap_or_default().to_string_lossy()
        )),
    }
}

/// 一行账单里各列的原始值；时间可能已经是表格里的日期单元格。
struct RowFields {
    time: Option<NaiveDateTime>,
    time_text: String,
    inout: String,
    amount: Amount,
    payee: String,
    item: String,
    note: String,
}

enum Amount {
    Text(String),
    /// 数字单元格，单位元
    Number(f64),
}

/// 返回 None 表示跳过该行（空行、分隔线、零金额或「/」中性交易）。
fn to_txn(row_no: usize, f: RowFields) -> Result<Option<RawTxn>, String> {
    let ndt = match f.time {
        Some(t) => t,
        None => {
            let s = f.time_text.trim();
            if s.is_empty() || s.starts_with("---") {
                return Ok(None);
            }
            TIME_FORMATS
                .iter()
                .find_map(|fmt| NaiveDateTime::parse_from_str(s, fmt).ok())
                .ok_or_else(|| format!("第 {row_no} 行交易时间无法解析：{s}"))?
        }
    };
    let ts = Local
        .from_local_datetime(&ndt)
        .earliest()
        .ok_or_else(|| format!("第 {row_no} 行交易时间在本地时区不存在：{ndt}"))?
        .timestamp_millis();

    let (neg, abs) = match f.amount {
        Amount::Number(v) => (v < 0.0, (v.abs() * 100.0).round() as i64),
        Amount::Text(s) => {
            let t = s.replace(['¥', '￥', ','], "");
            let t = t.trim();
            if t.is_empty() {
                return Ok(None);
            }
            let (neg, t) = match t.strip_prefix('-') {
                Some(rest) => (true, rest.trim()),
                None => (false, t),
            };
            let abs = crate::money::parse_cents_exact(t)
                .filter(|v| *v >= 0)
                .ok_or_else(|| format!("第 {row_no} 行金额无法解析：{s}"))?;
            (neg, abs)
        }
    };
    let inout = f.inout.trim();
    if inout == "/" || abs == 0 {
        return Ok(None);
    }
    let mut sign = if neg { -1 } else { 1 };
    if inout.contains('支') {
        sign = -1;
    }
    if inout.contains('收') {
        sign = 1;
    }
    let note = f.note.trim();
    Ok(Some(RawTxn {
        ts,
        amount: sign * abs,
        payee: f.payee.trim().to_string(),
        item: f.item.trim().to_string(),
        note: if note == "/" {
            String::new()
        } else {
            note.to_string()
        },
    }))
}

fn header_map<'a>(cells: impl Iterator<Item = &'a str>) -> HashMap<String, usize> {
    let mut m = HashMap::new();
    for (i, h) in cells.enumerate() {
        let h = h.trim();
        if !h.is_empty() {
            m.entry(h.to_string()).or_insert(i);
        }
    }
    m
}

fn parse_csv(bytes: &[u8]) -> Result<Vec<RawTxn>, String> {
    let bytes = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    let mut rdr = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(bytes);
    let mut rows = Vec::new();
    for rec in rdr.records() {
        let rec = rec.map_err(|e| format!("CSV 格式错误：{e}"))?;
        let row: Vec<String> = rec.iter().map(|s| s.trim().to_string()).collect();
        if row.iter().any(|s| !s.is_empty()) {
            rows.push(row);
        }
    }
    let head = rows
        .iter()
        .take(200)
        .position(|r| r.first().map(String::as_str) == Some(HEADER))
        .ok_or_else(|| format!("未找到表头：{HEADER}"))?;
    let col = header_map(rows[head].iter().map(String::as_str));
    let get = |r: &[String], name: &str| {
        col.get(name)
            .and_then(|&i| r.get(i))
            .cloned()
            .unwrap_or_default()
    };

    let mut out = Vec::new();
    for (i, r) in rows.iter().enumerate().skip(head + 1) {
        let fields = RowFields {
            time: None,
            time_text: get(r, HEADER),
            inout: get(r, "收/支"),
            amount: Amount::Text(get(r, "金额(元)")),
            payee: get(r, "交易对方"),
            item: get(r, "商品"),
            note: get(r, "备注"),
        };
        out.extend(to_txn(i + 1, fields)?);
    }
    Ok(out)
}

fn cell_text(c: Option<&Data>) -> String {
    match c {
        None | Some(Data::Empty) | Some(Data::Error(_)) => String::new(),
        Some(Data::String(s)) | Some(Data::DateTimeIso(s)) | Some(Data::DurationIso(s)) => {
            s.trim().to_string()
        }
        Some(Data::Float(f)) => f.to_string(),
        Some(Data::Int(i)) => i.to_string(),
        Some(Data::Bool(b)) => b.to_string(),
        Some(Data::DateTime(dt)) => dt.as_datetime().map(|d| d.to_string()).unwrap_or_default(),
    }
}

fn parse_xlsx(path: &Path) -> Result<Vec<RawTxn>, String> {
    let mut wb: Xlsx<_> = open_workbook(path).map_err(|e| format!("无法打开表格：{e}"))?;
    let sheets = wb.worksheets();
    let found = sheets.iter().find_map(|(_, range)| {
        range
            .rows()
            .take(101)
            .position(|r| r.iter().any(|c| cell_text(Some(c)) == HEADER))
            .map(|h| (range, h))
    });
    let (range, head) = found.ok_or_else(|| format!("未找到包含“{HEADER}”表头的工作表"))?;
    let rows: Vec<&[Data]> = range.rows().collect();
    let header: Vec<String> = rows[head].iter().map(|c| cell_text(Some(c))).collect();
    let col = header_map(header.iter().map(String::as_str));
    fn pick<'a>(col: &HashMap<String, usize>, r: &'a [Data], name: &str) -> Option<&'a Data> {
        col.get(name)
            .and_then(|&i| r.get(i))
            .filter(|c| !matches!(c, Data::Empty))
    }
    let cell = |r, name| pick(&col, r, name);
    let text = |r, name| cell_text(pick(&col, r, name));

    let mut out = Vec::new();
    for (i, r) in rows.iter().enumerate().skip(head + 1) {
        let time = match cell(r, HEADER) {
            Some(Data::DateTime(dt)) => dt.as_datetime(),
            _ => None,
        };
        let amount = match cell(r, "金额(元)") {
            Some(Data::Float(f)) => Amount::Number(*f),
            Some(Data::Int(v)) => Amount::Number(*v as f64),
            c => Amount::Text(cell_text(c)),
        };
        let fields = RowFields {
            time,
            time_text: text(r, HEADER),
            inout: text(r, "收/支"),
            amount,
            payee: text(r, "交易对方"),
            item: text(r, "商品"),
            note: text(r, "备注"),
        };
        // 行号与表格一致（range 可能不从第一行开始）
        let row_no = range.start().map_or(0, |(r0, _)| r0 as usize) + i + 1;
        out.extend(to_txn(row_no, fields)?);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn local_ms(s: &str) -> i64 {
        let ndt = NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S").unwrap();
        Local.from_local_datetime(&ndt).unwrap().timestamp_millis()
    }

    const CSV: &str = "\u{FEFF}微信支付账单明细,,,\n\
微信昵称：[某人],,,\n\
----------------------微信支付账单明细列表--------------------,,,\n\
交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n\
2025-09-01 12:00:00,商户消费,麦当劳,\"汉堡,套餐\",支出,¥25.50,零钱,支付成功,1,2,/\n\
2025-09-02 08:30:00,转账,张三,/,收入,\"¥1,000.00\",/,已收钱,3,4,还钱\n\
2025-09-03 09:00:00,零钱提现,招商银行,/,/,¥100.00,/,提现已到账,5,6,/\n\
\n\
2025/9/4 7:05,商户消费,地铁,/,支出,¥3,零钱,支付成功,7,8,/\n";

    #[test]
    fn parses_wechat_csv() {
        let out = parse_csv(CSV.as_bytes()).unwrap();
        assert_eq!(
            out,
            [
                RawTxn {
                    ts: local_ms("2025-09-01 12:00:00"),
                    amount: -2550,
                    payee: "麦当劳".into(),
                    item: "汉堡,套餐".into(),
                    note: "".into(),
                },
                RawTxn {
                    ts: local_ms("2025-09-02 08:30:00"),
                    amount: 100000,
                    payee: "张三".into(),
                    item: "/".into(),
                    note: "还钱".into(),
                },
                // 「/」中性交易（提现）与 xlsx 一样被跳过
                RawTxn {
                    ts: local_ms("2025-09-04 07:05:00"),
                    amount: -300,
                    payee: "地铁".into(),
                    item: "/".into(),
                    note: "".into(),
                },
            ]
        );
    }

    #[test]
    fn reports_bad_rows_with_line_numbers() {
        let bad = "交易时间,收/支,金额(元)\n2025-09-01 12:00:00,支出,¥1.234\n";
        assert!(parse_csv(bad.as_bytes()).unwrap_err().contains("第 2 行"));
        let no_header = "a,b\n1,2\n";
        assert!(
            parse_csv(no_header.as_bytes())
                .unwrap_err()
                .contains("表头")
        );
    }

    #[test]
    fn parses_wechat_xlsx_with_date_and_text_cells() {
        use rust_xlsxwriter::{ExcelDateTime, Format, Workbook};
        let dir = std::env::temp_dir().join(format!("cashflow-wechat-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("bill.xlsx");

        let mut wb = Workbook::new();
        let ws = wb.add_worksheet();
        ws.write(0, 0, "微信支付账单明细").unwrap();
        let header = [
            "交易时间",
            "交易类型",
            "交易对方",
            "商品",
            "收/支",
            "金额(元)",
            "备注",
        ];
        for (c, h) in header.iter().enumerate() {
            ws.write(2, c as u16, *h).unwrap();
        }
        let date_fmt = Format::new().set_num_format("yyyy-mm-dd hh:mm:ss");
        let dt = ExcelDateTime::parse_from_str("2025-09-05 18:20:30").unwrap();
        ws.write_datetime_with_format(3, 0, &dt, &date_fmt).unwrap();
        for (c, v) in ["商户消费", "瑞幸", "咖啡", "支出"].iter().enumerate() {
            ws.write(3, c as u16 + 1, *v).unwrap();
        }
        ws.write(3, 5, 9.9).unwrap();
        ws.write(3, 6, "/").unwrap();
        for (c, v) in [
            "2025-09-06 10:00:00",
            "转账",
            "李四",
            "/",
            "收入",
            "¥200.00",
            "红包",
        ]
        .iter()
        .enumerate()
        {
            ws.write(4, c as u16, *v).unwrap();
        }
        for (c, v) in [
            "2025-09-07 10:00:00",
            "提现",
            "银行",
            "/",
            "/",
            "¥50.00",
            "/",
        ]
        .iter()
        .enumerate()
        {
            ws.write(5, c as u16, *v).unwrap();
        }
        wb.save(&path).unwrap();

        let out = parse_file(&path).unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].ts, local_ms("2025-09-05 18:20:30"));
        assert_eq!(
            (out[0].amount, out[0].payee.as_str(), out[0].note.as_str()),
            (-990, "瑞幸", "")
        );
        assert_eq!(out[1].ts, local_ms("2025-09-06 10:00:00"));
        assert_eq!((out[1].amount, out[1].note.as_str()), (20000, "红包"));
    }
}
