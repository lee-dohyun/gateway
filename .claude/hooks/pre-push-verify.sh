#!/usr/bin/env bash
# PreToolUse(Bash) hook — `git push` 직전에 테스트를 강제한다.
#
# 왜: 이 저장소는 main push가 곧 프로덕션 배포다(self-hosted runner가 kubectl set image까지 수행).
# AGENTS.md는 "push 전 로컬 테스트"를 규칙으로 선언해 왔지만 강제 수단이 없어 실제로는 지켜지지 않았다.
# exit 2 = 도구 호출 차단(사유가 Claude에게 전달됨), exit 0 = 통과.
set -uo pipefail

INPUT=$(cat)
CMD=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty')

# git push가 아니면 관여하지 않는다.
case "$CMD" in
  *"git push"*) ;;
  *) exit 0 ;;
esac

# 의도적 우회: 커밋 메시지가 문서 전용이거나 사용자가 명시적으로 건너뛸 때.
if [ "${CLAUDE_SKIP_PUSH_VERIFY:-}" = "1" ]; then
  echo "pre-push-verify: CLAUDE_SKIP_PUSH_VERIFY=1 이므로 건너뜀" >&2
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$PWD}" || exit 0
[ -x ./gradlew ] || exit 0

echo "pre-push-verify: ./gradlew test 실행 중 (main push = 즉시 프로덕션 배포)" >&2
if ./gradlew test --console=plain -q; then
  echo "pre-push-verify: 테스트 통과" >&2
  exit 0
fi

cat >&2 <<'MSG'
push를 차단했습니다: ./gradlew test 가 실패했습니다.

이 저장소는 main push가 곧 프로덕션 배포이므로 실패한 코드를 밀면 그대로 운영에 반영됩니다.
실패 원인을 고친 뒤 다시 push하세요.

스키마 관련 실패라면 flyway-migration-guard 서브에이전트를 먼저 돌려보세요 —
엔티티 변경에 대응하는 마이그레이션이 빠졌을 때 ddl-auto=validate 환경에서는
테스트가 아니라 배포 시점에 터집니다.

검증 없이 진행해야 할 정당한 사유가 있다면 CLAUDE_SKIP_PUSH_VERIFY=1 을 설정하세요.
MSG
exit 2
