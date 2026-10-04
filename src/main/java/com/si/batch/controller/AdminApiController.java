package com.si.batch.controller;

import com.si.batch.common.BatchContext;
import com.si.batch.dao.PosSalesDao;
import com.si.batch.dao.ShopDao;
import com.si.batch.model.Banner;
import com.si.batch.model.Product;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private static final List<String> FUNCTIONS = List.of("FN_IMPORT", "FN_EXPORT", "FN_DAILY_CLOSE");

    private final BatchController batchController;
    private final DataSource dataSource;
    private final PosSalesDao dao = new PosSalesDao();
    private final ShopDao shopDao = new ShopDao();

    public AdminApiController(BatchController batchController, DataSource dataSource) {
        this.batchController = batchController;
        this.dataSource = dataSource;
    }

    // 기존 콘솔 메뉴를 대체: 관리자 수동 실행 (EXEC_TYPE=MANUAL -> 감사 메일 발송)
    @PostMapping("/batch/{functionId}")
    public ResponseEntity<Map<String, Object>> runBatch(@PathVariable String functionId, @RequestParam String date) {
        if (!FUNCTIONS.contains(functionId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "지원하지 않는 작업입니다: " + functionId));
        }
        BatchController.Result result = batchController.run(context(functionId, date));
        return ResponseEntity.ok(Map.of("code", result.code(), "message", result.message()));
    }

    // 선택한 영업일의 상품별 매출 + 최근 일일 집계
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam String date) throws Exception {
        if (!context("FN_SUMMARY", date).isValid()) {
            return ResponseEntity.badRequest().body(Map.of("message", "영업일자는 yyyyMMdd 형식이어야 합니다."));
        }

        try (Connection conn = dataSource.getConnection()) {
            Map salesMap = dao.selectProductSalesMap(conn, date);
            List<Map<String, Object>> products = new ArrayList<>();
            for (Product p : shopDao.selectAllProducts(conn)) {
                long[] v = (long[]) salesMap.getOrDefault(p.itemCode(), new long[]{0, 0});
                products.add(Map.of("itemCode", p.itemCode(), "itemName", p.itemName(),
                        "unitPrice", p.unitPrice(), "quantity", v[0], "amount", v[1]));
            }
            return ResponseEntity.ok(Map.of(
                    "products", products,
                    "recent", dao.selectRecentDailySummaries(conn, 14)));
        }
    }

    // 배치가 만든 파일 다운로드. 파일명은 서버가 조립 (경로 조작 방지)
    @GetMapping("/download")
    public ResponseEntity<FileSystemResource> download(@RequestParam String type, @RequestParam String date) {
        if (!context("FN_DOWNLOAD", date).isValid()) {
            return ResponseEntity.notFound().build();
        }
        String fileName = switch (type) {
            case "hourly" -> "hourly_sales_" + date + ".txt";
            case "daily" -> "daily_final_settlement_" + date + ".csv";
            default -> null;
        };
        File file = fileName == null ? null : new File(fileName);
        if (file == null || !file.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        (fileName.endsWith(".txt") ? "text/plain" : "text/csv") + "; charset=UTF-8"))
                .body(new FileSystemResource(file));
    }

    // 쇼핑몰 첫 화면 배너 관리 (지난/예약 배너 포함)
    @GetMapping("/banners")
    public List<Banner> banners() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.selectAllBanners(conn);
        }
    }

    @PostMapping("/banners")
    public ResponseEntity<Map<String, Object>> addBanner(@RequestBody Banner b) throws Exception {
        String error = validate(b);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("message", error));
        }
        try (Connection conn = dataSource.getConnection()) {
            shopDao.insertBanner(conn, clean(b));
        }
        return ResponseEntity.ok(Map.of());
    }

    @PutMapping("/banners/{id}")
    public ResponseEntity<Map<String, Object>> editBanner(@PathVariable int id, @RequestBody Banner b) throws Exception {
        String error = validate(b);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("message", error));
        }
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.updateBanner(conn, id, clean(b)) == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of());
        }
    }

    @DeleteMapping("/banners/{id}")
    public ResponseEntity<Map<String, Object>> removeBanner(@PathVariable int id) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.deleteBanner(conn, id) == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of());
        }
    }

    // 컬럼 길이에 맞춤. 링크는 쇼핑몰 안 주소(#/...)만 허용 (외부/스크립트 링크 차단)
    private String validate(Banner b) {
        if (b == null || blank(b.title())) return "제목을 입력해 주세요.";
        if (b.title().strip().length() > 60) return "제목은 60자까지 쓸 수 있습니다.";
        if (b.subtitle() != null && b.subtitle().strip().length() > 200) return "설명은 200자까지 쓸 수 있습니다.";
        if (!blank(b.linkUrl()) && (!b.linkUrl().strip().startsWith("#/") || b.linkUrl().strip().length() > 200))
            return "링크는 #/ 로 시작하는 쇼핑몰 주소만 쓸 수 있습니다.";
        if (b.linkText() != null && b.linkText().strip().length() > 30) return "버튼 문구는 30자까지 쓸 수 있습니다.";
        if (b.endAt() != null && b.startAt() != null && !b.endAt().isAfter(b.startAt())) return "종료 시각은 시작 시각보다 뒤여야 합니다.";
        if (b.endAt() != null && b.startAt() == null && !b.endAt().isAfter(LocalDateTime.now())) return "종료 시각은 지금보다 뒤여야 합니다.";
        return null;
    }

    // 빈 문자열은 NULL로, 시작 시각이 없으면 지금부터 (DATETIME은 소수 초를 반올림해 미래가 될 수 있어 초 단위로 자름)
    private Banner clean(Banner b) {
        return new Banner(0, b.title().strip(), nullIfBlank(b.subtitle()), nullIfBlank(b.linkUrl()), nullIfBlank(b.linkText()),
                b.sortOrder(), b.startAt() == null ? LocalDateTime.now().withNano(0) : b.startAt(), b.endAt());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return blank(s) ? null : s.strip();
    }

    private BatchContext context(String functionId, String date) {
        Map<String, String> params = new HashMap<>();
        params.put("JOB_ID", "JOB_POS_01");
        params.put("FUNCTION_ID", functionId);
        params.put("BATCH_DATE", date);
        params.put("EXEC_TYPE", "MANUAL");
        return new BatchContext(params);
    }
}
