# TracePilot · Prompt thiết kế từng giao diện

TracePilot theo dõi GitHub Actions, phát hiện workflow thất bại, phân tích log đã che thông tin nhạy cảm và lưu cách khắc phục đã được con người xác nhận vào kho tri thức của workspace.

**Ý tưởng nhận diện · Đường dấu vết.** Một đường mảnh nối các mốc từ lần chạy, job lỗi đến bằng chứng và báo cáo. Dấu chấm hồng đánh dấu điểm cần điều tra. Trên màn hình chi tiết, đường này giúp người dùng lần từ kết luận về nguồn gốc của nó.

## Cách dùng

1. Dán **Prompt nền + prompt 05** để tạo mẫu Dashboard trước. Dùng mẫu đã chọn làm tham chiếu khi tạo các màn còn lại trong cùng project Stitch hoặc công cụ AI thiết kế bạn dùng.
2. Mỗi lần dán **Prompt nền + một prompt đánh số**. Giữ lại thanh điều hướng, typography và component của mẫu. Prompt 11–12 là hai tab của màn 10, còn prompt 16 là panel mở từ biểu tượng chuông.
3. Nếu muốn kiểm tra luồng cốt lõi trước, tạo theo thứ tự **05 → 07 → 08 → 09 → 10 → 11 → 12 → 14 → 15**. Các màn 18–20 dành cho giai đoạn đo lường và vận hành sau MVP.

Prompt viết bằng tiếng Anh để có thể tái sử dụng giữa các công cụ. Chữ hiển thị trong ứng dụng dùng tiếng Việt. Các mã lỗi, tên job, repo và đoạn log giữ nguyên tiếng Anh.

## Prompt nền · Dán kèm từng màn hình

~~~text
Design TracePilot, a light, desktop-first web app for developers investigating GitHub Actions failures. Deliver one high-fidelity screen or screen state as requested. Reuse the reference screen's component sizes, type scale and navigation when supplied. Use Vietnamese UI copy with correct diacritics. Keep product names, workflow names, branch names, commit SHAs, model IDs, error codes and logs in their original form.

Visual identity
- White #FFFFFF is the dominant surface. Sky blue #0297DE marks navigation, active states and the main action. Pink #D83995 marks an investigation point or evidence reference. Aim for approximately 85% light surfaces, 10% blue accents and 5% pink accents. Use #F5FAFE for a subtle workspace canvas, #10243A for main text, #475569 for supporting text and #DDE8F0 for separators.
- Use #0297DE on prominent shapes and controls with dark, high-contrast labels. Use darker tones such as #00618E and #9D2368 for small blue and pink text on light backgrounds. Ensure WCAG AA text contrast and visible focus. Give every status a label and icon in addition to color. Use semantic green or amber sparingly for success or warning.
- Create a TracePilot mark from two sky-blue points joined by a line and one pink dot at a bend. Carry that visual into an evidence trace where it guides a task. Use faint connecting lines, small nodes and white space. Avoid large gradients, glass effects, 3D illustrations, heavy shadows and rows of decorative KPI cards.
- Use a clear Vietnamese-compatible sans serif such as Be Vietnam Pro, with JetBrains Mono for IDs, commits and logs. Use an 8 px spacing scale, 12 px card radii, subtle one-pixel borders, thin outline icons and comfortable row heights. Keep one visually dominant action per view.

App shell and responsiveness
- Reference desktop at 1440 px and mobile at 390 px. Use a 224 px left sidebar on desktop, a simple top bar with breadcrumb, notification bell and avatar. Show the active workspace “Release Lab” in the sidebar. Main navigation is “Tổng quan”, “Kho GitHub”, “Lần chạy”, “Sự cố”, “Kho tri thức” and “Cài đặt”. Use a shallow drawer and stacked content on mobile, with a visible way to return. Keep essential table fields visible and put secondary details behind row expansion. Allow horizontal scroll only inside code blocks or bounded data regions when necessary.
- For sign-in, sign-up and first workspace creation, use the compact onboarding shell with the TracePilot wordmark. Keep the same palette, typography and controls.
- Show a realistic populated desktop state and specify the mobile arrangement. Include relevant loading, empty, error, permission or disconnected variants as separate compact states when requested. Use inline errors, accessible labels, keyboard navigation and a clear retry or next action. Respect reduced-motion preferences. Refresh live status without moving the user's reading position or focus.
- All records are scoped to the active workspace. Respect server-provided permissions and show a concise explanation when an action is unavailable. GitHub App connection and TracePilot sign-in are separate. Keep CI write, deploy, auto-fix, billing and Kubernetes controls out of the prototype.

Shared demo data
- Workspace “Release Lab”, repository “release-lab/orders-api”, workflow “CI / Build & Test”, run “#482”, attempt 2, branch “main”, commit “9f3a2bd”, failed job “build-backend”.
- Run #482 attempt 2 failed at 08:42 GMT+7. The sanitized log was captured at 08:43 and AI analysis completed at 08:44. Incident “INC-014” is “JDK 17 không biên dịch được target 21”, severity “Cao”, assigned to “Minh Đạt”, status “Đang điều tra” in the initial state.
- Show the real relationship between demo evidence and conclusion. Source [1] is a sanitized log snippet showing “java -version → 17.0.12” and “error: release version 21 not supported”. Source [2] is an approved workspace article “Đồng bộ JDK trên GitHub Actions”. Describe the JDK mismatch as a suspected cause until a human confirms the fix. Label usage and metrics in the prototype as sample data when shown.
~~~

## 01 · Đăng nhập

~~~text
Design the TracePilot sign-in screen. Place the wordmark above a compact form on the left. On the right, draw a thin line linking “Workflow”, “Job lỗi” and “Bằng chứng”, with one pink investigation dot and the supporting line “Theo dấu lỗi CI từ job đến nguyên nhân”. Keep the illustration quiet and remove it on mobile.

Use the heading “Đăng nhập vào TracePilot”. Include labeled email and password fields, a show/hide password control and the primary button “Đăng nhập”. Add “Chưa có tài khoản? Tạo tài khoản”. Show the submitting state and a form-level error “Email hoặc mật khẩu chưa đúng”. Use a single form column on mobile, with the primary button easy to reach.
~~~

## 02 · Tạo tài khoản

~~~text
Design the sign-up screen using the same onboarding shell as sign-in. Use the heading “Tạo tài khoản TracePilot” and three labeled fields “Tên hiển thị”, “Email” and “Mật khẩu”. Put a short password requirement hint under the field using generic copy until the backend policy is known. Add the primary action “Tạo tài khoản” and link “Đã có tài khoản? Đăng nhập”.

Show inline missing or invalid field states and an email conflict message with a path back to sign-in. Preserve non-sensitive field values when a request fails. After account creation, guide the user to create a workspace. Keep the form narrow and comfortable on both desktop and mobile.
~~~

## 03 · Tạo workspace đầu tiên

~~~text
Design the first-workspace screen after account creation. Use a centered card with the heading “Tạo workspace đầu tiên” and a small two-step indicator “Workspace → GitHub”. Include fields “Tên workspace” and “Định danh workspace”. Suggest “release-lab” from “Release Lab”, make it editable and show a slug conflict inline. Use the primary action “Tạo workspace”.

Add one sentence “Workspace lưu các kho GitHub, sự cố và tài liệu của nhóm”. After creation, lead to GitHub connection. Make the active workspace clear and keep the MVP onboarding focused on this one workspace. Provide a retry state that preserves the form.
~~~

## 04 · Kết nối GitHub App và chọn repo

~~~text
Design a GitHub connection screen in the TracePilot app shell. Present a single progress strip with three steps “Kết nối GitHub App”, “Chọn kho được theo dõi” and “Chờ lần chạy đầu tiên”. In the initial state, show the primary action “Cài đặt GitHub App”, an external-link indicator and a short explanation of read access to GitHub Actions. Never ask for a token, private key or webhook secret in this UI.

After returning from GitHub, show the installation account and a compact list of repositories granted to the installation, such as “release-lab/orders-api”. Provide “Theo dõi các kho đã chọn” where supported. Show separate states for installation pending, no granted repositories, and connected but waiting for the first workflow run. When access is suspended or removed, explain what happened and link to “Kiểm tra quyền trên GitHub”. Stack the steps vertically on mobile.
~~~

## 05 · Dashboard tổng quan

~~~text
Design the TracePilot overview as a calm, light operations dashboard. The page title is “Tổng quan”. Below it, show one slim metrics strip with “Lần chạy trong 7 ngày”, “Lần chạy thất bại” and “Sự cố cần xử lý”. Keep the numbers legible and use a low-contrast background instead of separate saturated cards.

Use a 2:1 layout. The main column has “Cần điều tra” with incident title, severity, repo, status and time. Give INC-014 a small pink node on the left edge and a clear “Xem sự cố” action. The side column has “Dấu vết gần đây”, a vertical event rail showing run #482 failed at 08:42, sanitized log captured at 08:43 and AI analysis completed at 08:44. Underneath, show a compact recent runs list. Use a discrete live-status indicator and show a reconnecting state without blanking visible data.

Design a first-use variant with “Chưa kết nối kho GitHub” and a “Kết nối GitHub” action. For a connected repo with zero runs, show “Đang chờ lần chạy đầu tiên”. On mobile, prioritize the incident list before the event rail.
~~~

## 06 · Kho GitHub

~~~text
Design the “Kho GitHub” page. Start with a small connection summary showing the installation account and state. Below it, put a searchable table with columns “Kho”, “Nhánh mặc định” and “Theo dõi”. Include “release-lab/orders-api”, “main” and “Đang theo dõi”. Selecting the repo name opens “Lần chạy” with that repo preselected. An external GitHub link is a secondary row action.

Use “Quản lý kết nối” for users with permission. Keep tracked and inactive repositories distinct from installation states such as suspended or removed. Include separate empty states for no connected installation, no tracked repo and no search match. If the connection loses access, keep previous records readable and mark sync unavailable when those records are available.
~~~

## 07 · Danh sách lần chạy

~~~text
Design the “Lần chạy” index for scanning recent GitHub Actions runs. Put compact filters at the top for repository, branch, run state and date range, plus “Xóa bộ lọc” when active. Show a single responsive table with “Workflow / Kho”, “Run / Lượt”, “Nhánh / Commit”, “Trạng thái”, “Bắt đầu” and “Thời lượng”. Show “Đang chờ” or “Đang chạy” while a run is in progress and the final conclusion when it completes.

Add sample rows for #482 attempt 2 and #482 attempt 1 as separate entries. Use an icon plus “Thất bại” for the failed attempt and a subtle “Có sự cố” link where an incident exists. Clicking a row opens the exact attempt. Use a dash for a duration that cannot yet be calculated. Include first-load skeleton, no runs and no matches. On mobile, keep workflow, attempt and state in view.
~~~

## 08 · Chi tiết lần chạy và các job

~~~text
Design the workflow run detail for “CI / Build & Test”, run #482, attempt 2. Show the repository, branch, commit, outcome and time in a compact header, with “Mở trên GitHub” as an external action. Provide an attempt selector when more than one attempt exists. Make switching attempt update job rows and the linked incident together.

Use a narrow vertical job trace with separate rows for “validate-config”, “build-backend” and “integration-tests”. In this example validate-config succeeded, build-backend failed and integration-tests was skipped. Put the pink investigation node on build-backend and expose job status, time and duration when available. Show “Xem sự cố INC-014” as the primary action when this attempt has an incident. Include a state for jobs still syncing. Keep the same trace readable as a single column on mobile.
~~~

## 09 · Danh sách sự cố

~~~text
Design the “Sự cố” inbox for triage. Use tabs “Tất cả”, “Mở”, “Đang điều tra”, “Đã xử lý” and “Đã bỏ qua”. Add lightweight filters for repo, severity and assignee. Use a clean table with columns “Sự cố / Kho”, “Mức độ”, “Trạng thái”, “Phụ trách” and “Phát hiện lúc”. Show INC-014 as “Đang điều tra”, severity “Cao” and assignee “Minh Đạt”. Represent severity and lifecycle as different badges with text.

Include a concise empty state for no incidents and a separate no-match state with “Xóa bộ lọc”. Selecting a row opens its investigation page. On mobile, use one list item per incident with the title, state and severity visible at a glance and secondary metadata beneath.
~~~

## 10 · Chi tiết sự cố · Tổng quan

~~~text
Design the central investigation screen for INC-014. Put the incident title, severity and lifecycle badge at the top. Show breadcrumb “Sự cố / INC-014” and a link to run #482 attempt 2. Below the header, use three tabs “Tổng quan”, “Log đã che” and “Phân tích AI”. In the selected overview tab, show a two-column layout with the investigation trace on the left and “Thông tin sự cố” on the right.

On the trace, show “Workflow thất bại”, “Đã thu thập log” and “Có báo cáo AI” with timestamps and direct links to the run, the log tab and the analysis tab. On the right, show repo, failed job, assignee, detected time and resolved time when available. For users with permission, offer inline assignee selection and “Cập nhật trạng thái”. Use short confirmation copy for “Đã xử lý” or “Đã bỏ qua”. After the fix is confirmed, make “Tạo bản nháp tri thức” available where supported, linking to the article editor prefilled from this incident.

Show the AI state separately from the incident lifecycle. Use a stacked layout on mobile with status and assignee reachable near the top.
~~~

## 11 · Chi tiết sự cố · Tab log đã che

~~~text
Design the “Log đã che” tab inside the INC-014 investigation screen, using the same header and tabs. Place the sanitized log in a light monospace panel with selectable text, line numbering and controls “Tìm trong log” and “Sao chép đoạn log”. Provide a job filter only when snippets from multiple jobs are available. Label the panel “Đã che thông tin nhạy cảm”. Use a visible “[REDACTED]” marker in any masked sample line.

Show the demo “java -version” output and “error: release version 21 not supported”. Style the error line with a pale pink gutter and a text label. If the user opens source [1] from the AI report, scroll and focus the referenced snippet and provide a way back to the report. Include variants for logs being collected and logs unavailable. Keep search and copy controls visible on mobile and contain long lines within the log panel.
~~~

## 12 · Chi tiết sự cố · Tab phân tích AI và bằng chứng

~~~text
Design the “Phân tích AI” tab inside INC-014. Use an evidence-first layout with the report in the main column and a right rail titled “Bằng chứng”. Add a compact history selector for separate analysis attempts with timestamps. The current report includes “Tóm tắt”, “Nguyên nhân nghi ngờ” and “Cách khắc phục đề xuất”. Use numbered references beside the claims they support.

Show [1] as a link to the highlighted sanitized log snippet and [2] as a link to the approved knowledge article “Đồng bộ JDK trên GitHub Actions”. Suggest configuring CI to use JDK 21, then verifying with a fresh GitHub Actions run. Keep the proposed steps readable and copyable as text. Omit fabricated citations and confidence scores. If there is no matching approved knowledge, say “Chưa tìm thấy tài liệu tương tự”.

Include “Đang chờ”, “Đang phân tích”, “Hoàn tất” and “Phân tích thất bại” variants. The action “Phân tích lại”, where permitted, starts a new attempt and leaves earlier reports selectable. On failure, retain the incident and give the user a path to the log tab. For the later feedback phase, place “Phân tích này có hữu ích không?” below the report. On mobile, open evidence in a bottom sheet or reveal it inline from the numbered reference.
~~~

## 13 · Kho tri thức và tìm kiếm tương tự

~~~text
Design “Kho tri thức” as a searchable library of human-reviewed fixes. Make the main input invite a natural-language query such as “CI dùng Java 17 nhưng dự án yêu cầu Java 21”. Use a compact control with “Theo nội dung” and “Tìm lỗi tương tự”. For management browsing, show filters “Bản nháp”, “Đã duyệt” and “Đã lưu trữ” where the user has access.

Use a clean list with title, root-cause preview, status and update date. In similar-issue results show approved articles from this workspace with a matching excerpt. Use “Đồng bộ JDK trên GitHub Actions” as the sample result. Keep draft management clearly separate from approved semantic results. Include states for no articles, no drafts and no search match. Use a one-column list on mobile and keep the search field visible.
~~~

## 14 · Soạn và duyệt bản nháp tri thức

~~~text
Design the editor opened from INC-014 after the fix is confirmed. Use the heading “Bản nháp tri thức” and show a link back to the source incident. The form has “Tiêu đề”, “Nguyên nhân đã xác nhận” and “Cách khắc phục”. Prefill an editable example “Dùng JDK 21 cho CI của orders-api”. Place a compact read-only source reference in a side panel so the user can compare their writing with the original incident.

Use “Lưu bản nháp” as the primary action while editing. For users with approval permission, show “Duyệt tài liệu” on a saved draft with a review step explaining that approved content can be used in future AI analyses. Include validation errors, saving feedback and an unsaved-changes confirmation when leaving. On mobile, collapse the source reference above the form and keep the save action easy to reach.
~~~

## 15 · Chi tiết bài tri thức

~~~text
Design the article detail for “Đồng bộ JDK trên GitHub Actions”. Use a narrow reading column with a small “Đã duyệt” badge near the title and restrained metadata for author, approver and approval date where present. Organize the body into “Nguyên nhân đã xác nhận” and “Cách khắc phục”. Give commands or YAML snippets a light code block with a copy control.

Show a “Sự cố nguồn” link if the article has one. If opened from source [2] in an AI report, highlight the cited passage when its location is available and provide a way back to the report. Display “Bản nháp” and “Đã lưu trữ” as distinct states. Show editing or archive actions only when permitted. Keep the article readable as a single column on mobile.
~~~

## 16 · Thông báo

~~~text
Design the notifications panel opened by the bell in the app header. Use a 380 px panel on desktop and a full-width sheet on mobile. Provide tabs “Tất cả” and “Chưa đọc”. Show each notification with a label, timestamp and readable unread marker. Use demo entries “Phát hiện sự cố INC-014” and “Báo cáo AI cho INC-014 đã sẵn sàng”.

Selecting an item marks it read when supported and opens the incident overview or AI tab as appropriate. Add an explicit “Đánh dấu đã đọc” item action where available. Show “Bạn đã đọc hết thông báo” when the unread list is empty. Keep keyboard focus contained while the panel is open and return focus to the bell when it closes.
~~~

## 17 · Cài đặt workspace và thành viên

~~~text
Design “Cài đặt workspace” around the current workspace “Release Lab”. Show the workspace name and slug in a small information section. Below it, create a “Thành viên” table with name, email, role and membership state. Use the role labels “Chủ workspace”, “Quản trị viên” and “Thành viên”, with a small role glossary if needed.

Use “Thêm thành viên” for permitted users and a compact dialog to add an existing TracePilot account by email. Show a read-only member view for users without management permission. Keep role actions dependent on permissions returned by the app. Show a clear error for duplicate membership or an account that cannot be found. On mobile, display name, role and state in each row and move permitted actions into its menu.
~~~

## Sau MVP · Đo lường và vận hành

### 18 · Mức sử dụng AI

~~~text
Design “Mức sử dụng AI” as an internal analytics view for the active workspace. Put date range, model and operation filters at the top. Use a restrained chart for daily input and output tokens with a clear legend, tooltip and accessible labels. Add a compact summary for analysis count, tokens and “Chi phí ước tính (USD)”. Distinguish unknown cost from zero and label prototype figures as demo data.

Below the chart, show a table by provider, model and operation “Phân tích” or “Embedding”. Where measurements exist, show AI success rate and analysis duration with a clear time range. Include states for no records and partial usage data. Stack the summary and chart on mobile. Keep this view about operational usage.
~~~

### 19 · Nhật ký hoạt động

~~~text
Design a permission-gated “Nhật ký hoạt động” view for workspace audit history. Put filters for date, actor and action above a compact list. Each entry shows who acted, the action, the affected record and the time. Use examples “Minh Đạt đã chuyển INC-014 sang Đang điều tra” and “Quản trị viên đã duyệt một tài liệu”. Label automated entries “Hệ thống” when there is no human actor.

Use an expandable row for safe changed fields when available. Show a permission-denied state without leaking entry names or counts. Keep raw secrets, tokens and unfiltered metadata out of the UI. On mobile, use a simple chronological list with visible actor and action.
~~~

### 20 · Chẩn đoán webhook GitHub

~~~text
Design an optional permission-gated diagnostics view under GitHub connection settings called “Chẩn đoán webhook”. Lead with a simple connection state and the most recent event received when available. Show a filterable table with event type, repository, delivery ID, processing state, processing attempts and received time. Differentiate delivery processing attempts from GitHub workflow run attempts.

For a failed entry, open a detail panel with safe metadata, a sanitized error and the available received and processed timestamps. Use text states “Đã nhận”, “Đang xử lý”, “Đã xử lý”, “Thất bại” and “Đã bỏ qua”. Include no-event, disconnected and insufficient-permission states. Keep the view read-only and omit raw webhook payloads and secrets.
~~~
