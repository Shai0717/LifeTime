# LifeTime

Paper 서버용 수명 생존 콘텐츠입니다. 접속 중에는 시간이 계속 흐르고, 블록 채굴·설치, 공격·피격, 제작, 음식 섭취, 달리기 시작 등 지정된 행동에도 수명이 줄어듭니다. 수명이 0이 되면 즉사하며, 부활해도 관전자 상태로 탈락합니다.

## 요구 사항 및 빌드

- Paper 26.3 및 [Kolor 1.0.0](../Kolor/README.md)
- Java 25 이상
- 빌드에는 나란히 배치된 `LifeTime`과 `Kolor` 소스 폴더가 필요합니다. `LifeTime` 폴더에서 `..\Kolor\gradlew.bat build`를 실행합니다.
- 서버를 끄고 `Kolor/build/libs/Kolor-1.0.0.jar`와 `LifeTime/build/libs/LifeTime-1.0.0.jar`를 `plugins` 폴더에 넣은 뒤 서버를 시작합니다.
- Kotlin 표준 라이브러리는 Kolor의 Paper 플러그인 로더가 제공하며, 최초 로딩에는 Maven Central 미러에 접근해야 합니다.
- LifeTime은 현재 Paper 전용입니다. Kolor가 Folia를 지원하더라도 이 플러그인의 공유 상태와 플레이어 처리는 Folia에서 검증되지 않았습니다.
- 기존 `plugins/LifeTime/lifetimes.yml`과 `config.yml`을 그대로 사용합니다. 교체 전 서버를 종료하고 해당 폴더를 백업하세요.

## 기본 플레이

- 첫 접속 시 1시간의 수명이 주어집니다.
- 접속 중에는 실제 경과 시간만큼 수명이 줄어듭니다. 서버가 느려져도 경과한 시간이 다음 서버 틱에 반영됩니다. 로그아웃 중이거나 서버가 꺼져 있을 때는 줄어들지 않습니다.
- 수명이 다하면 즉사하고 관전자 모드로 전환됩니다. 다른 플레이어나 관리자로부터 수명을 받으면 탈락 전 게임 모드로 돌아옵니다. 원래 모드는 서버 재시작 후에도 유지됩니다.
- 이전 버전에서 이미 탈락해 원래 게임 모드가 저장되지 않은 관전자는 수명을 받으면 생존 모드로 복귀합니다.
- 플레이어가 적대적 `Monster` 몹을 처치하면 기본 30초를 얻습니다. 스포너·소환 몹도 해당하며, 플레이어 처치에는 보상이 없습니다.
- `/life pay <플레이어> <초>` 또는 `/수명 전달 <플레이어> <초>`로 접속 중인 다른 플레이어에게 수명을 보낼 수 있습니다. 보내는 사람에게 최소 1초가 남아야 하며 탈락한 플레이어도 받을 수 있습니다.
- 화면 오른쪽 점수판에서 접속 인원·생존 인원, 남은 수명 순위, 내 순위와 수명 게이지를 확인할 수 있습니다. 순위는 매초 갱신되며 상위 6명까지 표시됩니다.
- 보스바는 내 남은 수명을 색과 게이지로 표시하고, 수명을 쓰는 행동은 액션바에 소모량과 남은 시간이 표시됩니다. `/life` 또는 `/수명`으로 남은 시간을 확인할 수도 있습니다.

## 관리자 명령어

OP는 플레이어가 접속 중일 때 다음 명령어를 사용할 수 있습니다.

| 명령어 | 설명 |
| --- | --- |
| `/life give <플레이어> <초>` | 수명 추가 |
| `/life take <플레이어> <초>` | 수명 차감 |
| `/life set <플레이어> <초>` | 남은 수명 설정 |
| `/life reload` | 설정 다시 불러오기 |

## 설정

`plugins/LifeTime/config.yml`에서 시작 수명과 행동별 비용을 초 단위로 바꿀 수 있습니다. 비용을 `0`으로 지정하면 해당 행동의 수명 소모를 끕니다.

```yaml
starting-life-seconds: 3600
mob-kill-reward-seconds: 30
action-costs:
  block-break: 2
  block-place: 1
  entity-attack: 2
  damage-taken: 5
  craft: 1
  consume: 1
  sprint-start: 1
  interact: 0
```

`mob-kill-reward-seconds`를 `0`으로 지정하면 처치 보상을 끕니다. 설정에 잘못된 값이 있으면 시작 시 오류가 발생하며, `/life reload` 시에는 기존 설정을 유지합니다.

수명 데이터와 탈락 전 게임 모드는 `plugins/LifeTime/lifetimes.yml`에 저장됩니다. 서버 종료·플레이어 로그아웃·수명 전달·탈락 시 저장되며, 온라인 수명은 약 1분마다 자동 저장됩니다. 비정상 종료 시 마지막 저장 이후의 수명 변동은 사라질 수 있습니다.


<iframe src="https://github.com/sponsors/Shai0717/card" title="Sponsor Shai0717" height="225" width="600" style="border: 0;"></iframe>
