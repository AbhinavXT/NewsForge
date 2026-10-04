#!/usr/bin/env bash
# Cuts a release: checks the tree, runs the same checks CI does, tags, and pushes the tag.
#
#   tools/release.sh 1.4.2            tag v1.4.2 from main and push it
#   tools/release.sh 1.4.2 --no-push  tag only; push later with: git push origin v1.4.2
#   tools/release.sh 1.4.2 --skip-checks
#
# Pushing the tag is what starts .github/workflows/release.yml, which builds the signed
# APK and AAB and publishes the GitHub release. Nothing is built for distribution here;
# the local checks only exist so a broken tag is caught before it is public.
set -euo pipefail

# Under `set -e` a failing command ends the script with no message of its own; say which.
trap 'echo "error: \"$BASH_COMMAND\" failed (line $LINENO)" >&2' ERR

usage() {
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

version=""
push=true
checks=true
for arg in "$@"; do
    case "$arg" in
        --no-push) push=false ;;
        --skip-checks) checks=false ;;
        -h|--help) usage 0 ;;
        -*) echo "Unknown option: $arg" >&2; usage 1 ;;
        *) version="${arg#v}" ;;
    esac
done
[[ -n "$version" ]] || usage 1

fail() { echo "error: $*" >&2; exit 1; }

# The same rule the workflow applies, so a tag it would reject is never created.
[[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] \
    || fail "version must be MAJOR.MINOR.PATCH, got '$version'"
major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
(( minor <= 99 && patch <= 99 )) \
    || fail "minor and patch must be below 100 (versionCode is major*10000 + minor*100 + patch)"
version_code=$(( major * 10000 + minor * 100 + patch ))
tag="v$version"

cd "$(git rev-parse --show-toplevel)"

branch=$(git branch --show-current)
[[ "$branch" == "main" ]] || fail "releases are cut from main; you are on '$branch'"
[[ -z "$(git status --porcelain)" ]] || fail "working tree has uncommitted changes"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then fail "tag $tag already exists"; fi

echo "Fetching origin..."
# Only main, not --tags. Fetching every tag fails outright when an unrelated local tag
# differs from the remote one ("would clobber existing tag"), which has nothing to do
# with this release. The new tag is looked up on the remote directly instead.
git fetch --quiet origin main || fail "could not fetch origin/main"
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    fail "tag $tag already exists on origin"
fi
local_head=$(git rev-parse HEAD)
remote_head=$(git rev-parse origin/main)
if [[ "$local_head" != "$remote_head" ]]; then
    # Tagging a commit the remote does not have yet would publish code nobody has pushed.
    fail "main is not in sync with origin/main; push or pull first"
fi

previous=$(git describe --tags --abbrev=0 --match 'v*.*.*' 2>/dev/null || true)
if [[ -n "$previous" ]]; then
    echo
    echo "Changes since $previous:"
    git log --oneline "$previous..HEAD"
fi

if $checks; then
    echo
    echo "Running checks..."
    python3 tools/check_exhaustive_when.py
    ./gradlew :app:testDebugUnitTest :app:assembleRelease \
        -PversionName="$version" -PversionCode="$version_code" \
        --quiet
fi

git tag -a "$tag" -m "NewsForge $tag"
echo
echo "Created tag $tag at $(git rev-parse --short HEAD)."

if ! $push; then
    echo "Not pushed. When ready: git push origin $tag"
    exit 0
fi

read -r -p "Push $tag to origin and start the release build? [y/N] " answer
if [[ "$answer" =~ ^[Yy]$ ]]; then
    git push origin "$tag"
    repo=$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')
    echo "Release build: https://github.com/$repo/actions/workflows/release.yml"
else
    echo "Not pushed. Push later with: git push origin $tag"
    echo "Or remove the tag with:     git tag -d $tag"
fi
