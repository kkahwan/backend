package com.si.batch.controller;

import com.si.batch.dao.ShopDao;
import com.si.batch.model.Banner;
import com.si.batch.model.Notice;
import com.si.batch.model.Product;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

@RestController
public class ShopApiController {

    private final DataSource dataSource;
    private final ShopDao shopDao = new ShopDao();

    public ShopApiController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // 프론트가 POST 전에 받아가는 CSRF 토큰
    @GetMapping("/api/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    // 판매 중인 상품, 최근 등록 순
    @GetMapping("/api/products")
    public List<Product> products() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.selectOnSaleProducts(conn);
        }
    }

    @GetMapping("/api/notices")
    public List<Notice> notices() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.selectNotices(conn, 5);
        }
    }

    // 홈 상단 이벤트/홍보 배너
    @GetMapping("/api/banners")
    public List<Banner> banners() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return shopDao.selectActiveBanners(conn);
        }
    }

    // 주문은 결제 승인 후에만 저장 -> PaymentApiController

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
    }
}
