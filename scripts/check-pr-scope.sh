#!/usr/bin/env bash
# 检查一个 PR 的改动是否在 .github/pr-scope.conf 登记的范围内。
# 用法：check-pr-scope.sh [base] [head] [分支名] [清单路径]
# 也可用环境变量 BASE_SHA、HEAD_SHA、BRANCH_NAME、SCOPE_CONF 传入。
# 在被检查的 git 仓库目录内运行。全部问题一次列完，有问题退出 1，用法错误退出 2。

base=${1:-${BASE_SHA:-}}
head=${2:-${HEAD_SHA:-}}
branch=${3:-${BRANCH_NAME:-}}
conf=${4:-${SCOPE_CONF:-.github/pr-scope.conf}}
migration_dir='ruoyi-admin/src/main/resources/db/migration/'
jar_dir='ruoyi-union-sdk/lib/'

if [[ -z $base || -z $head || -z $branch ]]; then
  echo "用法：check-pr-scope.sh <base> <head> <分支名> [清单路径]" >&2
  exit 2
fi
if [[ ! -f $conf ]]; then
  echo "找不到清单文件：$conf" >&2
  exit 2
fi
branch=${branch#refs/heads/}

base_sha=$(git rev-parse --verify --quiet "$base^{commit}") || { echo "无法解析 base：$base" >&2; exit 2; }
head_sha=$(git rev-parse --verify --quiet "$head^{commit}") || { echo "无法解析 head：$head" >&2; exit 2; }
merge_base=$(git merge-base "$base_sha" "$head_sha") || { echo "base 与 head 没有共同祖先" >&2; exit 2; }

# 分支名对应的段名
section=
if [[ $branch =~ ^codex/(line-[0-9]+|t[0-9]+)- ]]; then
  section=${BASH_REMATCH[1]}
elif [[ $branch =~ ^chore/([a-z0-9]+)- ]]; then
  section=chore-${BASH_REMATCH[1]}
fi

# 读取本段规则
registered=false
mig_lo=()
mig_hi=()
allow=()
newonly=()
del=()
if [[ -n $section ]]; then
  current=
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    line=${line#"${line%%[![:space:]]*}"}
    line=${line%"${line##*[![:space:]]}"}
    [[ -z $line || $line == \#* ]] && continue
    if [[ $line =~ ^\[(.+)\]$ ]]; then
      current=${BASH_REMATCH[1]}
      [[ $current == "$section" ]] && registered=true
      continue
    fi
    [[ $current == "$section" ]] || continue
    key=${line%%[[:space:]]*}
    rest=${line#"$key"}
    rest=${rest#"${rest%%[![:space:]]*}"}
    case $key in
      migrations)
        read -r lo hi extra <<<"$rest"
        if [[ $lo =~ ^[0-9]+$ && $hi =~ ^[0-9]+$ && -z $extra ]]; then
          mig_lo+=("$((10#$lo))")
          mig_hi+=("$((10#$hi))")
        else
          echo "清单格式错误（migrations 需要两个数字）：$line" >&2
          exit 2
        fi
        ;;
      allow) allow+=("$rest") ;;
      new) newonly+=("$rest") ;;
      delete) del+=("$rest") ;;
      *)
        echo "清单格式错误（未知指令）：$line" >&2
        exit 2
        ;;
    esac
  done <"$conf"
fi

violations=()
fail() { violations+=("$1"); }

match_any() { # 路径 glob...
  local p=$1 g
  shift
  for g in "$@"; do
    # shellcheck disable=SC2053
    [[ $p == $g ]] && return 0
  done
  return 1
}

# base 上已有的迁移编号
declare -A base_versions=()
while IFS= read -r name; do
  if [[ $name =~ ^V([0-9]+)__.+\.sql$ ]]; then
    base_versions[$((10#${BASH_REMATCH[1]}))]=$name
  fi
done < <(git ls-tree --name-only "$base_sha" -- "$migration_dir" 2>/dev/null | sed 's|.*/||')
declare -A pr_versions=()

if [[ $registered == false ]]; then
  if [[ -z $section ]]; then
    fail "分支 $branch：分支名不符合 codex/line-N-*、codex/tNN-*、chore/<名>-* 的约定，未在 $conf 登记"
  else
    fail "分支 $branch：段 [$section] 未在 $conf 登记"
  fi
fi

count=0
while IFS= read -r -d '' status && IFS= read -r -d '' path; do
  count=$((count + 1))
  status=${status:0:1}
  [[ $status == T ]] && status=M

  # 迁移目录：对任何分支都生效
  if [[ $path == "$migration_dir"* ]]; then
    if [[ $status != A ]]; then
      if [[ $status == D ]]; then
        fail "$path：删除了已存在的迁移脚本，已合并的脚本不许改、不许删，改表只能新增脚本"
      else
        fail "$path：修改了已存在的迁移脚本，已合并的脚本不许改、不许删，改表只能新增脚本"
      fi
      continue
    fi
    name=${path#"$migration_dir"}
    if [[ $name == */* || ! $name =~ ^V([0-9]+)__.+\.sql$ ]]; then
      fail "$path：迁移文件名必须是 V<数字>__<说明>.sql，且直接放在迁移目录下"
    else
      num=$((10#${BASH_REMATCH[1]}))
      if [[ $registered == true ]]; then
        if [[ ${#mig_lo[@]} -eq 0 ]]; then
          fail "$path：本段 [$section] 没有登记迁移编号范围，不能新增迁移脚本"
        else
          ok=false
          for i in "${!mig_lo[@]}"; do
            if ((num >= mig_lo[i] && num <= mig_hi[i])); then ok=true; fi
          done
          if [[ $ok == false ]]; then
            ranges=
            for i in "${!mig_lo[@]}"; do ranges+="V${mig_lo[i]}~V${mig_hi[i]} "; done
            fail "$path：迁移编号 V$num 不在本段允许的范围（${ranges% }）内"
          fi
        fi
      fi
      if [[ -n ${base_versions[$num]:-} ]]; then
        fail "$path：迁移编号 V$num 与 base 已有脚本 ${base_versions[$num]} 重复"
      elif [[ -n ${pr_versions[$num]:-} ]]; then
        fail "$path：迁移编号 V$num 与本 PR 内的 ${pr_versions[$num]} 重复"
      fi
      pr_versions[$num]=$name
    fi
    size=$(git cat-file -s "$head_sha:$path" 2>/dev/null || echo 1)
    [[ $size -eq 0 ]] && fail "$path：新增了 0 字节的空文件"
    continue
  fi

  # 以下只在分支已登记时判断范围；未登记已经报过一条
  case $status in
    A)
      if [[ $path == *.jar && $path != "$jar_dir"* ]]; then
        fail "$path：新增了 jar 文件；除 $jar_dir 下的联盟 SDK 外不许提交 jar"
      fi
      if [[ $registered == true ]] && ! match_any "$path" "${allow[@]}" "${newonly[@]}"; then
        fail "$path：新增的路径不在 [$section] 的放行范围内（不要新建清单之外的目录或文件）"
      fi
      size=$(git cat-file -s "$head_sha:$path" 2>/dev/null || echo 1)
      [[ $size -eq 0 ]] && fail "$path：新增了 0 字节的空文件"
      ;;
    M)
      if [[ $registered == true ]]; then
        if match_any "$path" "${allow[@]}"; then
          :
        elif match_any "$path" "${newonly[@]}"; then
          fail "$path：该路径在 [$section] 里只能新增，不能修改已有文件"
        else
          fail "$path：修改的路径不在 [$section] 的放行范围内"
        fi
      fi
      ;;
    D)
      if [[ $registered == true ]] && ! match_any "$path" "${del[@]}"; then
        fail "$path：删除不在 [$section] 的 delete 放行范围内，现有文件默认一律不许删除（重命名按删除加新增算）"
      fi
      ;;
    *)
      fail "$path：未知的变更类型 $status"
      ;;
  esac
done < <(git diff --no-renames --name-status -z "$merge_base" "$head_sha")

if ((${#violations[@]} > 0)); then
  echo "改动范围检查失败（分支 $branch，共 ${#violations[@]} 项）："
  for v in "${violations[@]}"; do
    echo "  - $v"
  done
  echo
  echo "不要改清单绕过，停下在 PR 里说明。"
  exit 1
fi

echo "改动范围检查通过：分支 $branch，段 [$section]，共 $count 个文件变更（base $base_sha，merge-base $merge_base）。"
