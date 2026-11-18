#!/usr/bin/env python3
"""cs-pro REST 冒烟测试（零依赖，python3 标准库）。
前置：./run.sh 已启动（默认 8096）。无 LLM Key 时验证 FAQ 降级全链路。
用法：python3 smoke-test.py [base_url]
"""
import json
import sys
import time
import urllib.request
import urllib.error
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8096").rstrip("/")
ADMIN = {"X-Admin-Token": "cs-admin"}
RAND = uuid.uuid4().hex[:8]
passed = failed = 0


def call(method, path, body=None, headers=None, expect=200):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def ok(name, status, body):
    check(name, status == 200 and body.get("code") == "00000",
          f"status={status} body={json.dumps(body, ensure_ascii=False)[:200]}")
    return body.get("data")


print(f"==> cs-pro 冒烟测试 {BASE}")

# 1. 健康与鉴权
s, b = call("GET", "/api/health")
ok("健康检查", s, b)
s, b = call("GET", "/api/admin/overview")
check("管理端点无令牌拒绝 401", s == 401, f"status={s}")
s, b = call("GET", "/api/admin/overview", headers=ADMIN)
ov = ok("管理总览（带令牌）", s, b)

# 2. FAQ 检索
s, b = call("GET", "/api/kb/search?q=" + urllib.request.quote("打车计价"))
hits = ok("FAQ 检索", s, b)
check("FAQ 命中计价条目", bool(hits) and "计价" in hits[0]["entry"]["question"], str(hits)[:150])

# 3. 订阅状态（免费模式）：独立免费租户（ride-pro 可能已被历史兑换订阅，额度用例不可复用）
FREE_TENANT = f"SMOKE-FREE-{RAND}"
s, b = call("POST", "/api/admin/tenants", {"code": FREE_TENANT, "name": "冒烟免费租户"}, ADMIN)
ok("创建免费租户", s, b)
s, b = call("GET", f"/api/subscription/status?tenantCode={FREE_TENANT}&userId=SMOKE-{RAND}")
st = ok("订阅状态查询", s, b)
check("未订阅状态", st["subscribed"] is False, str(st.get("subscribed")))
check("默认免费额度 5", st["freeUsage"]["limit"] == 5, str(st.get("freeUsage")))

# 4. 会话 + 免费 FAQ 限次
uid = f"SMOKE-P-{RAND}"
s, b = call("POST", "/api/sessions", {"userRole": "PASSENGER", "userId": uid, "userName": "冒烟乘客", "tenantCode": FREE_TENANT})
sess = ok("创建会话", s, b)
sid = sess["id"]
check("免费租户会话", sess["tenantCode"] == FREE_TENANT)
check("会话返回访问凭证", bool(sess.get("accessToken")), str(sess.get("accessToken"))[:12])
STOKEN = {"X-Session-Token": sess["accessToken"]}
s, b = call("POST", "/api/sessions", {"userRole": "PASSENGER", "userId": uid + "-D", "userName": "默认映射"})
check("缺省角色映射 ride-pro", ok("默认租户 ride-pro", s, b)["tenantCode"] == "ride-pro")

s, b = call("POST", f"/api/sessions/{sid}/messages", {"content": "打车怎么计价？"}, STOKEN)
bot = ok("FAQ 直答", s, b)
check("直答含起步价且带知识库引用", "起步价" in bot["content"] and bot.get("kbRefs"), bot["content"][:80])
first_bot_id = bot["id"]

for i in range(4):
    s, b = call("POST", f"/api/sessions/{sid}/messages", {"content": "退款多久到账？"}, STOKEN)
    ok(f"免费额度第 {i + 2} 条", s, b)
s, b = call("POST", f"/api/sessions/{sid}/messages", {"content": "提现多久到账？"}, STOKEN)
check("第 6 条超额拒绝 409/A0409", s == 409 and b.get("code") == "A0409", f"status={s} code={b.get('code')}")

# 5. 激活码开通 + 重复兑换拒绝
s, b = call("POST", "/api/admin/codes/generate", {"days": 30, "count": 1}, ADMIN)
codes = ok("生成激活码", s, b)
code = codes[0]["code"]
s, b = call("POST", "/api/subscription/redeem", {"tenantCode": FREE_TENANT, "code": code})
sub = ok("激活码兑换开通", s, b)
check("订阅 30 天生效", sub["expireAt"] > time.time() * 1000 + 29 * 86400000)
s, b = call("POST", "/api/subscription/redeem", {"tenantCode": FREE_TENANT, "code": code})
check("重复兑换拒绝 409", s == 409, f"status={s}")

s, b = call("POST", f"/api/sessions/{sid}/messages", {"content": "打车怎么计价？"}, STOKEN)
bot2 = ok("订阅后不限次（无 Key 降级 FAQ）", s, b)
check("降级提示或 FAQ 答案", "智能助手暂不可用" in bot2["content"] or "起步价" in bot2["content"], bot2["content"][:80])

# 6. 工单全生命周期 + 幂等
rid = f"SMOKE-T-{RAND}"
treq = {"sessionId": sid, "category": "支付", "summary": "冒烟工单", "requestId": rid}
s, b = call("POST", "/api/tickets", treq)
t1 = ok("创建工单", s, b)
s, b = call("POST", "/api/tickets", treq)
t2 = ok("同 requestId 幂等重放", s, b)
check("幂等返回同一工单", t1["id"] == t2["id"])
tid = t1["id"]

s, b = call("POST", f"/api/admin/tickets/{tid}/resolve", {"solution": "x"}, ADMIN)
check("未认领直接解决拒绝 409", s == 409, f"status={s}")
s, b = call("POST", f"/api/admin/tickets/{tid}/assign", {"assignee": "冒烟客服"}, ADMIN)
ok("认领工单", s, b)
s, b = call("POST", f"/api/admin/tickets/{tid}/notes", {"author": "冒烟客服", "content": "已联系用户"}, ADMIN)
ok("添加备注", s, b)
s, b = call("POST", f"/api/admin/tickets/{tid}/resolve", {"solution": "已核实处理"}, ADMIN)
ok("解决工单", s, b)
s, b = call("POST", f"/api/admin/tickets/{tid}/close", {}, ADMIN)
ok("关闭工单", s, b)
s, b = call("POST", f"/api/admin/tickets/{tid}/close", {}, ADMIN)
check("重复关闭拒绝 409", s == 409, f"status={s}")
s, b = call("GET", f"/api/tickets?userId={uid}")
tl = ok("用户工单列表", s, b)
check("列表含本工单", any(t["id"] == tid for t in tl["list"]))

# 7. 评分
s, b = call("POST", "/api/ratings", {"targetType": "MESSAGE", "targetId": first_bot_id, "score": 5})
ok("消息评分", s, b)
s, b = call("POST", "/api/ratings", {"targetType": "MESSAGE", "targetId": first_bot_id, "score": 1})
check("重复评分拒绝 409", s == 409, f"status={s}")

# 8. 会话结束
s, b = call("POST", f"/api/sessions/{sid}/end", {}, STOKEN)
ok("结束会话", s, b)
s, b = call("POST", f"/api/sessions/{sid}/messages", {"content": "还在吗"}, STOKEN)
check("已结束会话拒绝消息 409", s == 409, f"status={s}")

# 9. FAQ 管理 CRUD
s, b = call("POST", "/api/admin/kb", {"question": f"冒烟问题{RAND}", "answer": "冒烟答案", "category": "其他"}, ADMIN)
kb = ok("新增 FAQ", s, b)
s, b = call("PUT", f"/api/admin/kb/{kb['id']}", {"question": f"冒烟问题{RAND}", "answer": "更新答案", "category": "其他"}, ADMIN)
ok("更新 FAQ", s, b)
s, b = call("DELETE", f"/api/admin/kb/{kb['id']}", headers=ADMIN)
ok("删除 FAQ", s, b)

# 10. 租户冻结准入
s, b = call("POST", "/api/admin/tenants", {"code": f"smoke-{RAND}", "name": "冒烟租户"}, ADMIN)
tn = ok("新建租户", s, b)
s, b = call("POST", f"/api/admin/tenants/{tn['id']}/status", {"status": "FROZEN"}, ADMIN)
ok("冻结租户", s, b)
s, b = call("POST", "/api/sessions", {"userRole": "PASSENGER", "userId": uid, "tenantCode": tn["code"]})
check("冻结租户拒绝建会话 409", s == 409, f"status={s}")
s, b = call("POST", f"/api/admin/tenants/{tn['id']}/status", {"status": "ACTIVE"}, ADMIN)
ok("解冻租户", s, b)

# 11. 统计与趋势
s, b = call("GET", "/api/admin/overview", headers=ADMIN)
ov2 = ok("统计总览", s, b)
check("会话/工单计数增长", ov2["sessions"]["total"] >= ov["sessions"]["total"] + 1
      and ov2["tickets"]["closed"] >= 1, str(ov2.get("sessions")))
s, b = call("GET", "/api/admin/stats/daily?days=7", headers=ADMIN)
trend = ok("7 天趋势", s, b)
check("趋势 7 行", len(trend) == 7)

# 12. LLM 审计（有 Key 出 OK；无 Key/降级出 ERROR/FALLBACK；对库历史不敏感）
audit_rows = 0
for st in ("OK", "ERROR", "FALLBACK"):
    s, b = call("GET", f"/api/admin/llm-calls?status={st}", headers=ADMIN)
    ok(f"LLM 审计查询 {st}", s, b)
    audit_rows += len(b.get("data") or [])
check("LLM 审计有记录", audit_rows >= 1, f"rows={audit_rows}")

# 13. 静态页面
for page in ["/", "/chat.html", "/subscription.html", "/agent.html", "/admin.html"]:
    req = urllib.request.Request(BASE + page)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            check(f"页面 {page}", resp.status == 200 and b"cs-pro" in resp.read())
    except Exception as e:
        check(f"页面 {page}", False, str(e))

print(f"\n==> 结果：{passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)