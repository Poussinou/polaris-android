git fetch origin --tags --prune-tags --force

git tag -d beta-release-candidate
git push --delete origin beta-release-candidate

git tag beta-release-candidate
git push --tags
