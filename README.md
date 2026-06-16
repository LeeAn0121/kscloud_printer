# KS Cloud Printer

`ks.cloud.ksclient`에서 대기표 데이터를 받아 BIXOLON USB 프린터로 출력하는
Android 애플리케이션입니다.

KS Client가 명시적 Intent로 출력 데이터를 전달하면 프린터 앱이 JSON 레이아웃을
Bitmap으로 렌더링하고, 이를 ESC/POS Raster 데이터로 변환하여 USB로 전송합니다.

## 처리 구조

```text
ks.cloud.ksclient
    |
    | 명시적 Intent + extras
    v
PrinterService
    |
    | 데이터 수신, 날짜/시간 생성, 로고 디코딩
    v
TicketLayoutRenderer
    |
    | JSON 레이아웃을 Bitmap으로 렌더링
    v
BixolonUsbPrinter
    |
    | Bitmap -> ESC/POS Raster -> USB Bulk Transfer
    v
BIXOLON USB 프린터
```

주요 파일:

- `PrinterService.java`: 외부 앱의 인쇄 요청 수신
- `TicketLayoutRenderer.java`: JSON 레이아웃 렌더링
- `BixolonUsbPrinter.java`: USB 탐색, Raster 변환 및 출력
- `ticket_layout.json`: 대기표 디자인
- `logo.bmp`: 앱에 포함된 기본 로고

## KS Client 연동

프린터 서비스의 패키지와 클래스:

```text
Package: ks.cloud.printer
Service: ks.cloud.printer.PrinterService
```

티켓 출력 Action:

```text
ks.cloud.printer.action.PRINT_TICKET
```

KS Client Kotlin 호출 예제:

```kotlin
fun printWaitingTicket(
    context: Context,
    number: String,
    waitingCount: Int,
    logoBytes: ByteArray?,
    layoutJson: String
) {
    val intent = Intent().apply {
        component = ComponentName(
            "ks.cloud.printer",
            "ks.cloud.printer.PrinterService"
        )
        action = "ks.cloud.printer.action.PRINT_TICKET"

        putExtra("title", "대기번호")
        putExtra("number", number)
        putExtra("waiting_count", waitingCount.toString())
        putExtra("image_bytes", logoBytes)
        putExtra("layout_json", layoutJson)
    }

    context.startService(intent)
}
```

호출 예제:

```kotlin
printWaitingTicket(
    context = this,
    number = "14",
    waitingCount = 13,
    logoBytes = logoByteArray,
    layoutJson = ticketLayoutJson
)
```

다른 앱의 서비스를 호출하므로 암시적 Intent 대신 위와 같이 명시적
`ComponentName`을 사용합니다.

## Intent 데이터 규격

| Key | 타입 | 필수 여부 | 설명 |
| --- | --- | --- | --- |
| `title` | `String` | 권장 | 제목. 일반적으로 `대기번호` |
| `number` | `String` | 권장 | 발급 번호. 예: `14` |
| `waiting_count` | `String` | 권장 | 대기 인수. 예: `13` |
| `image_bytes` | `ByteArray` | 선택 | PNG, BMP 또는 JPEG 로고 데이터 |
| `layout_json` | `String` | 신규 레이아웃 사용 시 필수 | 출력 레이아웃 JSON |

일반 텍스트 출력 Action:

```text
ks.cloud.printer.action.PRINT_TEXT
```

일반 텍스트 출력에는 `text` String extra를 사용합니다.

`time`과 `image_path` 상수도 정의되어 있지만 현재 티켓 출력 경로에서는 사용하지
않습니다.

## 데이터 처리 과정

### 1. 서비스 요청 수신

`PrinterService`는 Manifest에 `android:exported="true"`로 등록되어 있어 KS Client가
호출할 수 있습니다.

`IntentService` 기반이므로 전달된 인쇄 요청은 백그라운드 작업 스레드에서 순차
처리됩니다.

### 2. 날짜와 시간 생성

날짜와 시간은 KS Client에서 받지 않고 프린터 앱에서 요청 처리 시점에 생성합니다.

```text
date_text: yyyy년MM월dd일
time_text: HH시mm분
```

예:

```text
2026년06월12일
15시30분
```

### 3. 로고 디코딩

`image_bytes`가 있으면 `BitmapFactory.decodeByteArray()`로 Bitmap을 생성합니다.

KS Client의 로고 로딩 예:

```kotlin
val logoBytes = resources.openRawResource(R.raw.logo).use {
    it.readBytes()
}
```

Intent는 Android Binder 크기 제한을 받으므로 로고는 가능한 한 작은 파일로
전달해야 합니다.

### 4. 출력 방식 선택

`layout_json` 유무에 따라 출력 방식이 달라집니다.

```text
layout_json 있음  -> JSON 기반 신규 대기표
layout_json 없음  -> 기존 레거시 대기표
```

현재 디자인을 사용하려면 KS Client에서 반드시 `layout_json`을 전달해야 합니다.

## 레이아웃 JSON

기본 용지 폭은 384px이며, 각 항목의 좌표와 글꼴 크기를 JSON으로 관리합니다.

사용 가능한 주요 변수:

| 변수 | 치환 값 |
| --- | --- |
| `${title}` | 대기번호 |
| `${number}` | 발급 번호 |
| `${waiting_count}` | 현재 대기 인수 |
| `${date_text}` | 프린터 앱에서 생성한 날짜 |
| `${time_text}` | 프린터 앱에서 생성한 시간 |

지원 항목 타입:

- `text`: 텍스트, 위치, 정렬, 크기, 굵기
- `image`: 로고 이미지, 위치, 정렬, 최대 폭
- `rect`: 사각형
- `line`: 선

현재 로고는 가운데 정렬되고 최대 폭 240px로 비율을 유지하여 렌더링됩니다.
렌더링 후 마지막 콘텐츠 아래의 흰 공간은 제거됩니다.

현재 기본 레이아웃의 주요 값:

```text
paperWidth: 384
height:     680
logo y:     594
logo width: 240
```

## USB 출력

현재 연결 대상으로 지정된 BIXOLON USB 장치:

```text
Vendor ID:  5380
Product ID: 276
```

출력 순서:

1. 연결된 USB 장치에서 대상 프린터 탐색
2. Android USB 권한 확인
3. Bulk OUT Endpoint 탐색
4. USB 인터페이스 점유
5. Bitmap을 흑백 ESC/POS Raster 데이터로 변환
6. 프린터 초기화 명령 전송
7. Raster 이미지 전송
8. 용지 절단 명령 전송
9. USB 인터페이스 해제

주요 ESC/POS 명령:

```text
ESC @         프린터 초기화
GS v 0        Raster 이미지 출력
GS V B 0      용지 절단
```

티켓 Bitmap 출력 후 절단 전 별도 줄바꿈은 전송하지 않습니다.

## 빌드

환경:

```text
Application ID: ks.cloud.printer
Compile SDK:    35
Min SDK:        22
Target SDK:     28
```

Debug APK 빌드:

```powershell
.\gradlew.bat assembleDebug
```

생성 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 실행 및 권한 확인

Android Studio에서 `Run main`으로 실행하면 별도 테스트 화면을 표시하지 않습니다.
런처 Activity는 투명 화면으로 실행되어 USB 프린터 권한만 확인하고 바로 종료됩니다.

권한이 없는 경우 Android 시스템 USB 권한 팝업만 표시됩니다. 권한이 이미 있거나
프린터가 연결되어 있지 않으면 화면 없이 종료됩니다.

기존 테스트 버튼 화면과 자동 테스트 출력은 사용하지 않습니다.

## 설치 및 로그 확인

앱 설치:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

로그 확인:

```powershell
adb logcat -s KS_PRINTER_SERVICE KS_PRINTER
```

## 테스트 UI

현장 테스트용 Windows PowerShell UI를 제공합니다.

```powershell
.\test-print.ps1
```

또는 직접 실행:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\tools\printer-test-ui.ps1
```

기능:

- `adb devices -l` 결과를 읽어 ADB 기기를 선택
- 드롭다운에는 device id와 model만 표시
- 기기별 앱 설치 여부 로그 표시 및 설치된 기기 자동 선택
- APK 빌드
- 선택한 기기에 APK 설치
- USB 권한 확인 화면 실행
- 제목, 번호, 대기인수 값을 넣어 테스트 출력 요청

테스트 출력은 앱에 포함된 `assets/ticket_layout.json`과 `assets/logo.bmp`를
사용합니다. 실제 KS Client 연동에서는 `layout_json`과 `image_bytes`를 Intent로
전달하는 구조를 사용합니다.

Android의 백그라운드 서비스 실행 제한을 피하기 위해 테스트 UI는
`PrinterService`를 직접 `startservice`로 호출하지 않습니다. `MainActivity`를 잠깐
실행한 뒤 앱 내부에서 권한 확인과 테스트 출력을 진행합니다. 테스트 UI 또는
Activity 코드가 바뀐 뒤에는 `APK 빌드`와 `APK 설치`를 다시 실행해야 합니다.

## 운영 시 주의사항

- USB 권한이 없으면 서비스 출력이 실패합니다. 최초 권한 승인은 `Run main` 실행 시
  표시되는 Android 시스템 USB 권한 팝업에서 처리합니다.
- 현재 서비스는 외부 앱 호출을 위해 공개되어 있습니다. 배포 환경에서는 임의 앱의
  호출을 막기 위해 signature 권한을 추가하는 것이 안전합니다.
- `layout_json`이 없으면 신규 디자인이 아닌 레거시 티켓으로 출력됩니다.
- 서비스 내부 예외는 로그에 기록되며 KS Client로 성공 또는 실패 결과를 반환하지
  않습니다. 호출 결과가 필요하면 BroadcastReceiver 또는 ResultReceiver 기반 응답
  구조를 추가해야 합니다.
- `IntentService`는 최신 Android에서 deprecated 상태입니다. 현재 target SDK에서는
  동작하지만 향후에는 JobIntentService, WorkManager 또는 바인드 서비스 전환을
  검토해야 합니다.
