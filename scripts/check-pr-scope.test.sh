#!/usr/bin/env bash
# check-pr-scope.sh 的测试：在临时 git 仓库里造场景，逐项验证每个失败条件。
# 用法：bash scripts/check-pr-scope.test.sh
set -u

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
checker="$script_dir/check-pr-scope.sh"
mig=ruoyi-admin/src/main/resources/db/migration

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
repo="$work/repo"
mkdir -p "$repo"
cd "$repo" || exit 1
git init -q -b main .
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
git config core.autocrlf false

# 测试用清单，与真实清单同一语法
mkdir -p .github
cat > .github/pr-scope.conf <<'CONF'
# 测试清单
[line-9]
migrations 500 509
migrations 520 529
allow src/a/*
new src/n/*
delete src/del/*
allow ruoyi-union-sdk/lib/*

[line-1]
allow only-line1/*

[line-10]
migrations 600 699
allow src/ten/*

[chore-guardrails]
allow src/guard/*
allow ruoyi-admin/*
delete src/guard/*
CONF

mkdir -p src/a src/n src/del src/guard src/ten only-line1 "$mig"
echo a > src/a/x.txt
echo n > src/n/existing.txt
echo d > src/del/y.txt
echo old > "$mig/V1__baseline.sql"
echo old > "$mig/V510__old.sql"
echo g > src/guard/g.txt
git add -A
git commit -q -m base
git tag base0

pass=0
failn=0
case_no=0

# 在 base0 上建分支并执行 $1（函数名）改动，提交，运行检查。
# 用法：run_case <说明> <分支名> <期望退出码> <改动函数> [期望输出包含的片段...]
run_case() {
  local desc=$1 branch=$2 want=$3 mutate=$4
  shift 4
  case_no=$((case_no + 1))
  git checkout -q -B "$branch" base0
  "$mutate"
  git add -A
  git commit -q --allow-empty -m change
  local out code ok=true frag
  out=$(bash "$checker" base0 HEAD "$branch" .github/pr-scope.conf 2>&1)
  code=$?
  if [[ $code -ne $want ]]; then ok=false; fi
  for frag in "$@"; do
    [[ $out == *"$frag"* ]] || ok=false
  done
  if [[ $ok == true ]]; then
    pass=$((pass + 1))
    printf 'PASS %2d  %s\n' "$case_no" "$desc"
  else
    failn=$((failn + 1))
    printf 'FAIL %2d  %s（期望退出码 %s，实际 %s）\n' "$case_no" "$desc" "$want" "$code"
    printf '%s\n' "$out" | sed 's/^/        | /'
  fi
}

m_ok() {
  echo new > src/n/added.txt
  echo mod >> src/a/x.txt
  echo fresh > src/a/added.txt
  echo 'select 1;' > "$mig/V500__ok.sql"
  git rm -q src/del/y.txt
}
m_unregistered() { echo x > src/a/f.txt; }
m_outside() { mkdir -p random/dir; echo x > random/dir/f.txt; }
m_delete() { git rm -q src/a/x.txt; }
m_modify_new() { echo more >> src/n/existing.txt; }
m_empty() { : > src/a/empty.txt; }
m_jar_outside() { echo j > src/a/tool.jar; }
m_jar_lib() { mkdir -p ruoyi-union-sdk/lib; echo j > ruoyi-union-sdk/lib/sdk.jar; }
m_mig_modify() { echo more >> "$mig/V1__baseline.sql"; }
m_mig_delete() { git rm -q "$mig/V1__baseline.sql"; }
m_mig_name() { echo 's;' > "$mig/V501_bad.sql"; echo 's;' > "$mig/V502__x.txt"; }
m_mig_range() { echo 's;' > "$mig/V515__x.sql"; }
m_mig_dup_base() { echo 's;' > "$mig/V510__new.sql"; }
m_mig_dup_pr() { echo 's;' > "$mig/V503__a.sql"; echo 's;' > "$mig/V503__b.sql"; }
m_mig_second_range() { echo 's;' > "$mig/V525__ok.sql"; }
m_mig_empty() { : > "$mig/V504__empty.sql"; }
m_mig_none() { echo 's;' > "$mig/V500__x.sql"; }
m_rename() { git mv src/a/x.txt src/n/x.txt; }
m_many() {
  mkdir -p random; echo x > random/f.txt
  git rm -q src/a/x.txt
  mkdir -p src/a; : > src/a/empty.txt
}
m_line1_only() { echo x > only-line1/f.txt; }
m_guard_ok() { echo x >> src/guard/g.txt; }
m_guard_mig() { echo more >> "$mig/V1__baseline.sql"; }

run_case "全部合规：新增、修改、允许的删除、范围内迁移编号" codex/line-9-ok 0 m_ok "改动范围检查通过"
run_case "分支未在清单登记（段不存在）" codex/line-77-x 1 m_unregistered "未在" "不要改清单绕过，停下在 PR 里说明"
run_case "分支名不符合约定" feature/foo 1 m_unregistered "未在"
run_case "路径不在放行范围（乱建目录）" codex/line-9-x 1 m_outside "random/dir/f.txt" "不在 [line-9] 的放行范围"
run_case "删除不在 delete 范围" codex/line-9-x 1 m_delete "src/a/x.txt" "delete"
run_case "new 范围内修改已有文件" codex/line-9-x 1 m_modify_new "src/n/existing.txt" "只能新增"
run_case "新增 0 字节空文件" codex/line-9-x 1 m_empty "src/a/empty.txt" "0 字节"
run_case "新增 jar 不在 lib 下" codex/line-9-x 1 m_jar_outside "src/a/tool.jar" "jar"
run_case "jar 在 ruoyi-union-sdk/lib 下不触发 jar 规则" codex/line-9-x 0 m_jar_lib "改动范围检查通过"
run_case "修改已存在的迁移脚本" codex/line-9-x 1 m_mig_modify "V1__baseline.sql" "修改了已存在的迁移脚本"
run_case "删除已存在的迁移脚本" codex/line-9-x 1 m_mig_delete "V1__baseline.sql" "删除了已存在的迁移脚本"
run_case "迁移文件名不符合 V<数字>__*.sql" codex/line-9-x 1 m_mig_name "V501_bad.sql" "V502__x.txt" "迁移文件名必须"
run_case "迁移编号不在本段范围" codex/line-9-x 1 m_mig_range "V515__x.sql" "不在本段允许的范围"
run_case "迁移编号与 base 重复" codex/line-9-x 1 m_mig_dup_base "V510__new.sql" "与 base 已有脚本 V510__old.sql 重复"
run_case "迁移编号在本 PR 内重复" codex/line-9-x 1 m_mig_dup_pr "与本 PR 内的"
run_case "多行 migrations 的第二段范围可用" codex/line-9-x 0 m_mig_second_range "改动范围检查通过"
run_case "迁移脚本是 0 字节空文件" codex/line-9-x 1 m_mig_empty "V504__empty.sql" "0 字节"
run_case "本段无 migrations 登记时不许新增迁移" codex/line-1-x 1 m_mig_none "没有登记迁移编号范围"
run_case "重命名按删除加新增算" codex/line-9-x 1 m_rename "src/a/x.txt" "重命名按删除加新增算"
run_case "多个问题一次全部列出" codex/line-9-x 1 m_many "random/f.txt" "src/a/x.txt" "src/a/empty.txt" "共 3 项"
run_case "line-1 的范围不套用到 line-10（分支匹配带连字符）" codex/line-10-x 1 m_line1_only "only-line1/f.txt"
run_case "chore/guardrails 分支改自己范围内的文件" chore/guardrails-x 0 m_guard_ok "改动范围检查通过"
run_case "已有迁移对任何分支都不许改（含护栏分支）" chore/guardrails-x 1 m_guard_mig "修改了已存在的迁移脚本"

# 场景：base 在分叉后又前进。PR 只应比较到 merge-base，不应把 main 新增的文件算成删除；
# 同时 main 上新增的迁移编号要参与重复检测。
case_no=$((case_no + 1))
git checkout -q main
git checkout -q -B codex/line-9-late base0
echo 's;' > "$mig/V520__mine.sql"
git add -A
git commit -q -m mine
git checkout -q main
echo 's;' > "$mig/V520__theirs.sql"
echo z > src/n/main-only.txt
git add -A
git commit -q -m "main advances"
main_tip=$(git rev-parse HEAD)
git checkout -q codex/line-9-late
out=$(bash "$checker" "$main_tip" HEAD codex/line-9-late .github/pr-scope.conf 2>&1)
code=$?
if [[ $code -eq 1 && $out == *"与 base 已有脚本 V520__theirs.sql 重复"* && $out != *"main-only.txt"* ]]; then
  pass=$((pass + 1))
  printf 'PASS %2d  %s\n' "$case_no" "base 前进后：用 merge-base 比较，且 base 新增编号参与重复检测"
else
  failn=$((failn + 1))
  printf 'FAIL %2d  %s（退出码 %s）\n' "$case_no" "base 前进后的比较" "$code"
  printf '%s\n' "$out" | sed 's/^/        | /'
fi

# 场景：清单文件缺失时是用法错误（退出 2）
case_no=$((case_no + 1))
bash "$checker" base0 HEAD codex/line-9-x /nonexistent.conf >/dev/null 2>&1
code=$?
if [[ $code -eq 2 ]]; then
  pass=$((pass + 1))
  printf 'PASS %2d  %s\n' "$case_no" "清单文件不存在时退出 2"
else
  failn=$((failn + 1))
  printf 'FAIL %2d  %s（退出码 %s）\n' "$case_no" "清单文件不存在" "$code"
fi

echo
echo "通过 $pass，失败 $failn，共 $case_no"
[[ $failn -eq 0 ]]
