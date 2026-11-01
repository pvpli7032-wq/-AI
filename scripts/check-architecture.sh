#!/usr/bin/env bash
# 检查一次改动的位置是否符合 AGENTS.md「文件放哪里」。只检查位置，不按线限制文件：
#   1. 不许新增顶层目录（含顶层文件）；
#   2. ruoyi-system/src/main/java/com/ruoyi/system/cps/ 根目录不许放文件，业务代码放 <业务包>/ 下；
#   3. 新增的 Java 文件必须落在 AGENTS.md 表里的位置；
#   4. 新增的 Mapper XML 必须落在 ruoyi-system/src/main/resources/mapper/cps/。
#
# 用法：bash scripts/check-architecture.sh [base] [head]
#   也可以用环境变量 BASE_SHA、HEAD_SHA 传入。默认 base=origin/main、head=HEAD。
#   在被检查的 git 仓库目录内运行。全部问题一次列完，有问题退出 1，用法错误退出 2。

base=${1:-${BASE_SHA:-origin/main}}
head=${2:-${HEAD_SHA:-HEAD}}

base_sha=$(git rev-parse --verify --quiet "$base^{commit}") || { echo "无法解析 base：$base" >&2; exit 2; }
head_sha=$(git rev-parse --verify --quiet "$head^{commit}") || { echo "无法解析 head：$head" >&2; exit 2; }
merge_base=$(git merge-base "$base_sha" "$head_sha") || { echo "base 与 head 没有共同祖先" >&2; exit 2; }

# AGENTS.md「文件放哪里」里放 Java 的位置（业务逻辑单独判断，见 cps_dir）
cps_dir='ruoyi-system/src/main/java/com/ruoyi/system/cps/'
mapper_xml_dir='ruoyi-system/src/main/resources/mapper/cps/'
java_dirs=(
  'ruoyi-union-sdk/src/main/java/com/ruoyi/union/'
  "$cps_dir"
  'ruoyi-admin/src/main/java/com/ruoyi/web/controller/miniapp/'
  'ruoyi-admin/src/main/java/com/ruoyi/web/controller/cps/'
  'ruoyi-quartz/src/main/java/com/ruoyi/quartz/task/'
)

violations=()
fail() { violations+=("$1"); }

in_java_dir() { # 路径
  local path=$1 dir
  for dir in "${java_dirs[@]}"; do
    [[ $path == "$dir"* ]] && return 0
  done
  return 1
}

# base 上已有的顶层目录（含顶层文件）
declare -A top_level=()
while IFS= read -r name; do
  [[ -n $name ]] && top_level[$name]=1
done < <(git ls-tree --name-only "$base_sha")

added=0
while IFS= read -r -d '' status && IFS= read -r -d '' path; do
  [[ ${status:0:1} == A ]] || continue
  added=$((added + 1))

  # 1. 不许新增顶层目录
  top=${path%%/*}
  if [[ -z ${top_level[$top]:-} ]]; then
    fail "$path：新增了顶层目录（或顶层文件）$top，AGENTS.md：不要新建顶层目录"
    continue
  fi

  # 2. cps/ 根目录不许放文件
  if [[ $path == "$cps_dir"* ]]; then
    rest=${path#"$cps_dir"}
    if [[ $rest != */* ]]; then
      fail "$path：文件直接放在 cps/ 根目录，业务代码要放 ${cps_dir}<业务包>/ 下"
      continue
    fi
  fi

  # 3. 新增 Java 文件的位置
  if [[ $path == *.java ]]; then
    if [[ $path == *'/src/test/'* ]]; then
      : # 测试：对应模块的 src/test/
    elif in_java_dir "$path"; then
      :
    else
      fail "$path：新增的 Java 文件不在 AGENTS.md「文件放哪里」的位置内，Java 只能放联盟接口、业务逻辑 ${cps_dir}<业务包>/、小程序接口、后台接口、定时任务，或对应模块的 src/test/"
    fi
  fi

  # 4. 新增 Mapper XML 的位置
  if [[ $path == *.xml && $path == *'/src/main/resources/mapper/'* && $path != "$mapper_xml_dir"* ]]; then
    fail "$path：新增的 Mapper XML 只放 ${mapper_xml_dir}（AGENTS.md「文件放哪里」）"
  fi
done < <(git diff --no-renames --name-status -z "$merge_base" "$head_sha")

if ((${#violations[@]} > 0)); then
  echo "架构位置检查失败（共 ${#violations[@]} 项）："
  for v in "${violations[@]}"; do
    echo "  - $v"
  done
  echo
  echo "只检查位置，不按线限制文件。确实放不下时在 PR 里说明，不要绕过检查。"
  exit 1
fi

echo "架构位置检查通过：base $base_sha，merge-base $merge_base，新增 $added 个文件。"
