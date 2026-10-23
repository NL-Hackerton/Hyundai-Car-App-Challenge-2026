## 보완 결론

**[문헌] Pleos는 `CarUxRestrictionsManager`·`CarUxRestrictions` 연동을 공식 개발 지침에 직접 명시한다.** 따라서 이 방법은 단순히 AAOS 기반이라는 점에서 추정한 호환성이 아니다. **Pleos의 문서상 지원 근거는 확보했으며, 대상 에뮬레이터·양산차에서의 실제 동작만 미검증**으로 구분하면 된다.

### 1. parked-only 화면 제한 계약

| 확인 항목 | 공식 계약과 해석 |
|---|---|
| Pleos 연동 방식 | Pleos 앱은 플랫폼이 제공하는 `CarUxRestrictions`를 적용해야 한다. `CarUxRestrictionsManager.registerListener()` 등록 후 `onUxRestrictionsChanged()`로 변경 상태를 받도록 안내한다.[추가 1] |
| DO 미선언 앱 | Pleos 공식 기본 정책은 **Parking에서 허용, Drive에서 불허**다. 정책은 플랫폼 버전·양산차에 따라 달라질 수 있다고 명시한다.[추가 2] |
| 현재 제한 조회 | Google 공식 API는 `getCurrentCarUxRestrictions()`로 현재 제한을 조회하고 `registerListener()`·`unregisterListener()`로 변경 구독을 관리한다.[추가 3] |
| 제한 판단 | `isRequiresDistractionOptimization() == false`이면 비-DO Activity를 표시할 수 있다. `true`이면 DO Activity만 표시할 수 있다.[추가 4] |
| 주의할 의미 | `false`는 **비-DO 화면 표시가 허용되는 UX 상태**다. 물리적 주차 완료·안전한 하차·목적지 도착을 증명하는 센서값으로 표현하지 않는다. Google은 절대적인 주행 상태보다 플랫폼 UX 제한 정보에 반응하라고 안내한다.[추가 4] |
| 비트마스크 주의 | `getActiveRestrictions() == 0`만으로 화면을 허용하면 안 된다. `UX_RESTRICTIONS_BASELINE == 0`이어도 `isRequiresDistractionOptimization()`이 `true`일 수 있다.[추가 4] |

**기획서에 사용할 표현**

> [문헌·설계] 앱은 DO를 선언하지 않는 주차 중 이용 화면으로 기획한다. Pleos가 제공하는 차량 UX 제한 상태를 조회·구독하며, 비-DO 화면 표시가 허용되는 경우에만 준비 항목 확인을 제공한다. 문 열림을 주차 완료로 추론하지 않는다. 대상 환경의 제한 적용 동작은 구현 단계에서 검증한다.

### 2. fail-closed UX 설계 제안

다음은 **공식 API를 활용한 [가정·설계]**이며 구현·실측 결과가 아니다.

- **초기 상태는 잠금이다.** Manager 연결과 현재 제한 조회가 유효하게 완료되기 전에는 체크리스트 입력·확인 동작을 허용하지 않는다.
- **명시적인 `isRequiresDistractionOptimization() == false` 상태에서만** 준비 확인 화면의 상호작용을 허용한다.
- 제한이 `true`로 바뀌거나 연결 해제·조회 실패 등으로 상태를 신뢰할 수 없으면 상호작용을 중단하고 비-DO 화면을 닫거나 이탈시킨다. 단순히 버튼만 비활성화한 화면을 계속 표시해도 된다고 가정하지 않는다.
- 재연결·화면 재진입 시에는 과거 허용값을 그대로 재사용하지 않고 현재 상태를 다시 확인한다.
- 도어 이벤트는 준비 항목의 **확인 맥락**으로만 사용한다. 도어가 열려도 UX 잠금을 해제하거나 짐·반려동물 항목을 자동 완료하지 않는다.

이 구성은 별도의 속도·기어 API를 앱이 조합하여 주차를 추정하는 부담을 줄인다. 다만 **UX 제한 계약은 C의 안전한 사용 경계를 보완할 뿐, 수동 체크리스트 대비 효용까지 입증하지는 않는다.** 차별성은 여전히 비교 검증 대상이다.

### 3. 후석 도어 영역 주장 축소

**현재 확보한 공식 예제는 `ROW_1_LEFT`에 한정된다.** 후석·트렁크 지원이 검증되었다고 쓰면 안 된다.

- 공식 capability 문서는 `checkDoorPositionCapabilities`의 결과로 `CapabilityConfig`와 영역별 `Map<Area, AreaBounds>`를 받는 구조를 설명한다.
- 예제는 `DoorArea(DoorValue.ROW_1_LEFT)`의 범위를 조회한다. 읽기는 `READ`·`READ_WRITE` 접근 수준에서만 진행한다.[추가 5]
- 공식 Sample App은 Door Position 구독·해제에 **같은 Listener와 같은 영역**을 사용하도록 안내한다. 전체 기능이 지원되어도 특정 영역 조회는 실패할 수 있다.[기존 6]
- 따라서 **후석 영역의 상수명·대상 차량 지원·상태 수신은 미확인**이다. 대상 환경에서 실제 반환된 지원 영역과 읽기 범위를 확인한 뒤 해당 영역만 사용해야 한다.

**기획서 권고 문장**

> [문헌·설계] 도어 연동은 capability 결과에서 읽기 가능한 것으로 확인된 영역에만 적용한다. 공식 예제에서 확인한 영역은 `ROW_1_LEFT`이며, 후석·트렁크 영역 지원은 확정하지 않았다. 지원되지 않는 영역은 ‘미지원/확인 불가’로 표시하며, 열림·닫힘이나 준비 완료 상태를 만들어 내지 않는다.

### 추가 출처

1. **Pleos 주행 제한 개발 지침**  
   https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide  
   발행주체: 현대자동차그룹 Pleos Playground. 확인일: 2026-10-23.  
   근거: `CarUxRestrictionsManager` 연동과 변경 콜백 적용을 직접 안내한다.

2. **Pleos 지역별 주행 제한 정책**  
   https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy  
   발행주체: 현대자동차그룹 Pleos Playground. 확인일: 2026-10-23.  
   근거: DO 미선언 앱은 Parking 허용·Drive 불허가 기본이다. 플랫폼 버전·양산차별 차이 가능성을 명시한다.

3. **Google `CarUxRestrictionsManager` API**  
   https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager  
   발행주체: Google Android Developers. 페이지 갱신일: 2025-10-09. 확인일: 2026-10-23.  
   근거: 현재 제한 조회·리스너 등록·해제 계약이다.

4. **Google `CarUxRestrictions` API**  
   https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions  
   발행주체: Google Android Developers. 확인일: 2026-10-23.  
   근거: 비-DO Activity 표시 조건, OEM·시장별 차이, `BASELINE == 0` 해석이다.

5. **Pleos capability 안내**  
   https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability  
   발행주체: 현대자동차그룹 Pleos Playground. 확인일: 2026-10-23.  
   근거: 영역별 허용 범위와 `ROW_1_LEFT` 예제다.

**남은 미확인:** 대상 환경의 Manager 연결·콜백 전달·플랫폼 화면 차단 실제 동작, Activity 선언 세부 심사 조건, 후석·트렁크 영역 지원이다. 구현·설치·실측은 수행하지 않았다.
