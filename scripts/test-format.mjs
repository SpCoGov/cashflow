import assert from "node:assert/strict";
import test from "node:test";
import { fmtDateTime, fmtYuan, fmtYuanGrouped, parseDateTime, parseQuickAmount, yuanToCents } from "../src/format.ts";

test("元转分：四舍五入远离零，拒绝非法输入", () => {
  assert.equal(yuanToCents("12.5"), 1250);
  assert.equal(yuanToCents("-1.005"), -101);
  assert.equal(yuanToCents("1,234.564"), 123456);
  assert.equal(yuanToCents("+.5"), 50);
  assert.equal(yuanToCents("0"), 0);
  for (const bad of ["", ".", "abc", "1.2.3", "--1"]) assert.equal(yuanToCents(bad), null, bad);
});

test("快速添加：无符号为支出，+ 为收入", () => {
  assert.equal(parseQuickAmount("25"), -2500);
  assert.equal(parseQuickAmount("+25"), 2500);
  assert.equal(parseQuickAmount("-3.1"), -310);
  assert.equal(parseQuickAmount("x"), null);
});

test("金额格式化使用整数运算", () => {
  assert.equal(fmtYuan(-5), "-0.05");
  assert.equal(fmtYuan(123456), "1234.56");
  assert.equal(fmtYuanGrouped(-123456789), "-1,234,567.89");
  assert.equal(fmtYuanGrouped(0), "0.00");
});

test("时间解析与格式化互逆，拒绝不存在的日期", () => {
  const ms = parseDateTime("2025-09-01 08:05:09");
  assert.equal(fmtDateTime(ms), "2025-09-01 08:05:09");
  assert.equal(parseDateTime("2025/9/1 8:05"), new Date(2025, 8, 1, 8, 5).getTime());
  assert.equal(parseDateTime("1756656000"), 1756656000000);
  assert.equal(parseDateTime("1756656000000"), 1756656000000);
  assert.equal(parseDateTime("2025-02-30 00:00:00"), null);
  assert.equal(parseDateTime("2025-09-01 24:00"), null);
  assert.equal(parseDateTime("昨天"), null);
});
