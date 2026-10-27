#!/usr/bin/env bash
# 从 CHANGELOG.md 和 CHANGELOG.zh-CN.md 取出某个版本的那一节，拼成双语发布说明输出到 stdout。
# 用法：release-notes.sh <versionName> [repo]，repo 形如 owner/name，用来把相对链接改成绝对链接。
set -euo pipefail

version="$1"
repo="${2:-}"

# 取 "## <version>" 到下一个 "## " 之间的内容，去掉首尾空行
section() {
  awk -v v="$version" '
    /^## / { if (found) exit; found = ($2 == v); next }
    found { lines[++n] = $0 }
    END {
      s = 1; while (s <= n && lines[s] == "") s++
      e = n; while (e >= s && lines[e] == "") e--
      for (i = s; i <= e; i++) print lines[i]
    }
  ' "$1"
}

en=$(section CHANGELOG.md)
zh=$(section CHANGELOG.zh-CN.md)

# 少写任何一份都直接失败，避免发出没有说明的版本
for pair in "CHANGELOG.md:$en" "CHANGELOG.zh-CN.md:$zh"; do
  if [ -z "${pair#*:}" ]; then
    echo "::error::${pair%%:*} has no section for $version" >&2
    exit 1
  fi
done

notes=$(printf '%s\n\n---\n\n%s\n' "$en" "$zh")

# Release 页面上相对链接打不开，指向这个 tag 下的文件
if [ -n "$repo" ]; then
  notes=$(printf '%s\n' "$notes" | sed -E "s#\]\(([^)#:]+)\)#](https://github.com/$repo/blob/v$version/\1)#g")
fi

printf '%s\n' "$notes"
