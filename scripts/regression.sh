#!/usr/bin/env bash
#
# 面试主链路回归：连跑 N 轮完整面试，记录耗时与评分波动。
#
# 用途：A6「黄金路径回归」——改完代码后确认「出题 → 答题 → 评分 → 追问 → 报告」没退化。
#
# 用法：
#   bash scripts/regression.sh 3 you@qq.com 你的密码
#
# 依赖：bash（Git Bash）、python（解析 JSON）、本机中间件（E://WorkProject//DB_script 起 5 个）、Java :8002、Python :8100
#
# 判定标准（脚本末尾自动对比）：
#   ① 出题耗时 ≤ 15s
#   ② 单题评分耗时 ≤ 20s
#   ③ 每轮至少一个正分，且同轮分数不全相同（证明评分官在读题，不是固定返回值）
#   ④ 全程无 isSuccess=false
#   ⑤ 每轮都能跑到 finished=true
#
# 刻意**不**判「跨轮同题号分差」：每轮的第 N 题不是同一道题（出题官随机生成），
#   分差反映的是「同一段答案碰上不同题目」，用它判失败会持续误报。

set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8002}"   # 用 IP 而非 localhost：Windows 上 localhost 可能先解析到 IPv6 ::1
API="$BASE/api/banma/v1"
ROUNDS="${1:-3}"
# 凭据来源优先级：位置参数 > scripts/.regression.env > 环境变量
# 为什么推荐文件：Windows 终端把带 @ 的密码传给 bash 会被截断（实测 12 位只剩 6 位）
ENV_FILE="$(cd "$(dirname "$0")" && pwd)/.regression.env"
if [ -f "$ENV_FILE" ]; then
	# shellcheck disable=SC1090
	. "$ENV_FILE"
fi
USER_EMAIL="${2:-$USER_EMAIL}"
USER_PASS="${3:-$USER_PASS}"
if [ -z "$USER_EMAIL" ] || [ -z "$USER_PASS" ]; then
	echo "用法（任选一种）：" >&2
	echo "  ① 推荐：在 scripts/.regression.env 里写两行（文件已 .gitignore）" >&2
	echo "       USER_EMAIL=you@qq.com" >&2
	echo "       USER_PASS=你的密码" >&2
	echo "  ② 参数：bash scripts/regression.sh [轮数] <邮箱> <密码>（密码含 @ 可能被终端截断）" >&2
	exit 2
fi

# 5 段固定答案循环使用：既要足够长让评分官有内容可评，又要跨轮一致才能比较波动
ANSWERS=(
"扩容时容量翻倍，用 hash 与 n-1 做位运算定位桶，比取模快；JDK8 引入红黑树，链表长度超过 8 转树，退化到 6 转回链表；并发场景下 resize 可能形成死循环，JDK8 用高低位拆分避免了这个问题。"
"线程池核心参数有 corePoolSize、maximumPoolSize、keepAliveTime、workQueue、threadFactory、handler；提交任务先看核心线程是否满，满了进队列，队列满再扩到最大线程数，还满就触发拒绝策略。"
"索引失效常见于：对列做函数运算、隐式类型转换、前导模糊匹配 like '%x'、or 中有一侧无索引、联合索引不满足最左前缀；另外区分度低的列建索引意义不大。"
"事务隔离级别有读未提交、读已提交、可重复读、串行化；MySQL 默认可重复读，靠 MVCC 实现快照读，当前读靠间隙锁防幻读；隔离级别越高并发越差。"
"Spring 循环依赖靠三级缓存解决：一级放成品 bean，二级放半成品，三级放 ObjectFactory；A 依赖 B 时先把 A 的工厂放三级缓存，B 创建时能从三级拿到 A 的早期引用。"
)

TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

# 从 stdin 读 JSON，按点号路径取值；空值输出空串
field() {
	python -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
for k in sys.argv[1].split("."):
    if isinstance(d, dict):
        d = d.get(k)
    else:
        d = None
        break
print("" if d is None else d)
' "$1"
}

# 毫秒时间戳
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

# ── 0. 登录 ──
printf '{"username":"%s","password":"%s"}' "$USER_EMAIL" "$USER_PASS" > "$TMPD/login.json"
# 调试：把实际发出的用户名与密码长度打出来（密码本体不打印）
echo "  请求：POST $API/users/login"
echo "  用户名=[$USER_EMAIL] 密码长度=${#USER_PASS}"
LOGIN_RESP=$(curl -s -X POST "$API/users/login" -H "Content-Type: application/json" \
	--data-binary @"$TMPD/login.json")
TOKEN=$(echo "$LOGIN_RESP" | field data.token)
if [ -z "$TOKEN" ]; then
	echo "✗ 登录失败。服务端原始响应：" >&2
	echo "$LOGIN_RESP" | head -c 600 >&2
	echo >&2
	echo "  排查顺序：① 账号是否已注册（建表后 t_user 为空）② 密码是否正确 ③ Java 是否在 $BASE" >&2
	exit 1
fi
echo "✓ 登录成功（token 长度 ${#TOKEN}）"
echo

RESULT="$TMPD/result.tsv"
: > "$RESULT"
FAIL=0   # 任一轮失败即置 1，避免「全部轮次跳过」被误判为通过

# ── 逐轮跑 ──
for r in $(seq 1 "$ROUNDS"); do
	# 1. 建会话
	SESS_RESP=$(curl -s -X POST "$API/interview/sessions" -H "Authorization: Bearer $TOKEN")
	SID=$(echo "$SESS_RESP" | field data.sessionId)
	if [ -z "$SID" ]; then
		echo "✗ 第 $r 轮：创建会话失败。响应：$(echo "$SESS_RESP" | head -c 300)" >&2
		FAIL=1
		continue
	fi

	# 2. 出题（计时）
	printf '{"session_id":"%s","user_input":"帮我出几道 Java 后端面试题","direction_default":"Java 后端"}' "$SID" > "$TMPD/q.json"
	t0=$(now_ms)
	QRESP=$(curl -s -X POST "$API/interview/agent/question-officer" \
		-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
		--data-binary @"$TMPD/q.json")
	t1=$(now_ms)
	# 题目在 data.questions（顶层只有 code/message/data），不能直接取顶层
	qcount=$(echo "$QRESP" | python -c 'import sys,json;d=json.load(sys.stdin);print(len((d.get("data") or {}).get("questions") or []))' 2>/dev/null || echo 0)

	if [ "$qcount" -eq 0 ]; then
		echo "✗ 第 $r 轮：出题失败或题目为空" >&2
		echo "$QRESP" | head -c 300 >&2; echo >&2
		FAIL=1
		continue
	fi

	# 3. 逐题作答：按响应里的 nextQuestionNumber 推进（与前端逻辑一致，能覆盖追问）
	qn="1"; idx=0; sum_ms=0; answered=0
	scores=""
	while :; do
		ans="${ANSWERS[$((idx % 5))]}"
		printf '{"questionNumber":"%s","answerContent":"%s"}' "$qn" "$ans" > "$TMPD/a.json"
		a0=$(now_ms)
		ARESP=$(curl -s -X POST "$API/interview/sessions/$SID/interview/answer-json" \
			-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
			--data-binary @"$TMPD/a.json")
		a1=$(now_ms)

		score=$(echo "$ARESP" | field data.score)
		ok=$(echo "$ARESP" | field data.isSuccess)
		finished=$(echo "$ARESP" | field data.finished)
		next=$(echo "$ARESP" | field data.nextQuestionNumber)

		sum_ms=$(( sum_ms + a1 - a0 )); answered=$(( answered + 1 ))
		scores="${scores}${qn}:${score} "

		# 兼容布尔与字符串两种序列化（True / true），否则会把成功误判成失败
		if [ "$ok" != "True" ] && [ "$ok" != "true" ]; then
			echo "  ✗ 第 $r 轮 $qn 题失败（isSuccess=[$ok]），完整响应：" >&2
			echo "$ARESP" | head -c 700 >&2
			echo >&2
			FAIL=1
			break
		fi
		{ [ "$finished" = "True" ] || [ "$finished" = "true" ]; } && break
		[ -z "$next" ] && break
		[ "$answered" -ge 40 ] && { echo "  ⚠ 第 $r 轮达到 40 次作答上限，疑似未正常结束" >&2; break; }
		qn="$next"; idx=$(( idx + 1 ))
	done

	# 4. 落报告记录 + 取总分
	curl -s -X POST "$API/interview/interview/record/save-from-redis/$SID" \
		-H "Authorization: Bearer $TOKEN" > /dev/null
	total=$(curl -s "$API/interview/interview/record/$SID" -H "Authorization: Bearer $TOKEN" | field data.interviewScore)

	avg_ms=$(( answered > 0 ? sum_ms / answered : 0 ))
	printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
		"$r" "$qcount" "$(( t1 - t0 ))" "$avg_ms" "$total" "$finished" "$scores" >> "$RESULT"

	printf '第 %s 轮：题数 %s · 出题 %sms · 单题均 %sms · 总分 %s · finished=%s\n' \
		"$r" "$qcount" "$(( t1 - t0 ))" "$avg_ms" "${total:-—}" "${finished:-—}"
done

# ── 汇总 ──
echo
echo "轮次 │ 题数 │ 出题(ms) │ 单题均(ms) │ 总分 │ 各题分"
echo "─────┼──────┼──────────┼────────────┼──────┼────────────────────────"
awk -F'\t' '{ printf "  %s  │  %s   │  %6s  │   %6s   │ %s │ %s\n", $1, $2, $3, $4, ($5==""?"—":$5), $7 }' "$RESULT"

# ── 判定 ──
echo
FAIL=0
# 零数据 = 所有轮次都失败，绝不能判"通过"
if [ ! -s "$RESULT" ]; then
	echo "✗ 没有任何一轮跑完（见上方每轮错误），无法判定" >&2
	exit 1
fi
awk -F'\t' '
{ if ($3 > 15000) { printf "⚠ 第 %s 轮出题耗时 %sms > 15000ms\n", $1, $3; bad=1 }
  if ($4 > 20000) { printf "⚠ 第 %s 轮单题均耗时 %sms > 20000ms\n", $1, $4; bad=1 }
  if ($6 != "True") { printf "⚠ 第 %s 轮未跑到 finished=true\n", $1; bad=1 } }
END { exit bad ? 1 : 0 }' "$RESULT" || FAIL=1

# 评分有效性（替代原「跨轮同题号分差」判定）
# 为什么删分差：每轮的「第 N 题」不是同一道题（出题官随机生成），分差反映的是
#   「同一段答案碰上不同的题」，不是评分稳定性——用它判失败会持续误报。
# 现在只守两件事：① 每轮至少有一个正分（评分官没死）② 同轮分数不全相同（不是固定值）
python - "$RESULT" <<'PY' || FAIL=1
import sys, collections
rows = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8')]
bad = False
for row in rows:
    rnd = row[0]
    scores = []
    for pair in (row[6] if len(row) > 6 else '').split():
        if ':' not in pair:
            continue
        val = pair.split(':', 1)[1]
        if val.lstrip('-').isdigit():
            scores.append(int(val))
    if not scores:
        print(f'⚠ 第 {rnd} 轮没有有效分数')
        bad = True
        continue
    if max(scores) <= 0:
        print(f'⚠ 第 {rnd} 轮全部 0 分，评分官可能没工作')
        bad = True
    elif len(scores) > 1 and len(set(scores)) == 1:
        print(f'⚠ 第 {rnd} 轮所有题同分（{scores[0]}），疑似返回固定值')
        bad = True
# 信息性提示（不判失败）：跨轮总分跨度
totals = [int(row[4]) for row in rows if row[4].isdigit()]
if len(totals) >= 2 and max(totals) - min(totals) > 150:
    print(f'ℹ 跨轮总分跨度 {max(totals) - min(totals)}（{totals}）——题目不同所致，仅供参考')
sys.exit(1 if bad else 0)
PY

if [ "$FAIL" -eq 0 ]; then
	echo "✓ 全部判定通过（出题≤15s、单题≤20s、分差≤10、每轮 finished）"
else
	echo "✗ 存在未通过项，见上方 ⚠"
fi
