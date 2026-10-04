-- 쇼핑몰 상품/공지 테이블 (기존 POS 테이블은 그대로). 여러 번 실행해도 안전
CREATE TABLE IF NOT EXISTS PRODUCT (
    ITEM_CODE   VARCHAR(20)  NOT NULL PRIMARY KEY,
    ITEM_NAME   VARCHAR(50)  NOT NULL,
    UNIT_PRICE  INT          NOT NULL CHECK (UNIT_PRICE >= 0),
    DESCRIPTION VARCHAR(500) NULL,
    ON_SALE     TINYINT(1)   NOT NULL DEFAULT 1,      -- 0이면 쇼핑몰/주문에서 제외 (판매 이력은 남김)
    CREATED_AT  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX IDX_PRODUCT_CREATED (CREATED_AT)
);

CREATE TABLE IF NOT EXISTS NOTICE (
    NOTICE_ID  INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    TITLE      VARCHAR(100) NOT NULL,
    CONTENT    VARCHAR(1000) NULL,
    PINNED     TINYINT(1)   NOT NULL DEFAULT 0,       -- 1이면 맨 위 고정
    CREATED_AT DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 기존 ProductCatalog enum 4개를 옮겨 옴 (등록일은 '최신 상품' 순서가 보이도록 하루씩 차이)
INSERT IGNORE INTO PRODUCT (ITEM_CODE, ITEM_NAME, UNIT_PRICE, CREATED_AT) VALUES
    ('ITM_HS_01', '1번 상품', 1000,  NOW() - INTERVAL 3 DAY),
    ('ITM_HS_02', '2번 상품', 10000, NOW() - INTERVAL 2 DAY),
    ('ITM_HS_03', '3번 상품', 100,   NOW() - INTERVAL 1 DAY),
    ('ITM_HS_04', '4번 상품', 5000,  NOW());

INSERT INTO NOTICE (TITLE, CONTENT, PINNED, CREATED_AT)
SELECT * FROM (
    SELECT '하늘마켓이 문을 열었어요' AS TITLE, '필요한 상품을 장바구니에 담아 바로 주문해 보세요.' AS CONTENT, 1 AS PINNED, NOW() - INTERVAL 3 DAY AS CREATED_AT
    UNION ALL SELECT '전 상품 무료배송', '금액과 상관없이 배송비가 들지 않아요.', 0, NOW() - INTERVAL 2 DAY
    UNION ALL SELECT '새 상품이 들어왔어요', '홈 화면 ''최신 상품''에서 확인해 보세요.', 0, NOW()
) seed
WHERE NOT EXISTS (SELECT 1 FROM NOTICE);

-- 홈 상단 이벤트/홍보 배너. 기간(START_AT~END_AT) 안에 있는 것만 SORT_ORDER 순으로 노출
CREATE TABLE IF NOT EXISTS BANNER (
    BANNER_ID  INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    TITLE      VARCHAR(60)  NOT NULL,
    SUBTITLE   VARCHAR(200) NULL,
    LINK_URL   VARCHAR(200) NULL,                     -- 사이트 안 주소만 (예: #/products). 없으면 버튼 숨김
    LINK_TEXT  VARCHAR(30)  NULL,
    SORT_ORDER INT          NOT NULL DEFAULT 0,
    START_AT   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    END_AT     DATETIME     NULL,                     -- NULL이면 계속 노출
    CREATED_AT DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO BANNER (TITLE, SUBTITLE, LINK_URL, LINK_TEXT, SORT_ORDER, START_AT, END_AT)
SELECT * FROM (
    SELECT '하늘마켓 오픈 기념' AS TITLE, '이번 달은 모든 상품을 배송비 없이 보내 드려요.' AS SUBTITLE, '#/products' AS LINK_URL, '상품 둘러보기' AS LINK_TEXT, 1 AS SORT_ORDER, NOW() - INTERVAL 3 DAY AS START_AT, NOW() + INTERVAL 30 DAY AS END_AT
    UNION ALL SELECT '새 상품이 들어왔어요', '이번 주에 새로 들어온 상품을 먼저 만나 보세요.', '#/product/ITM_HS_04', '새 상품 보기', 2, NOW() - INTERVAL 1 DAY, NULL
    UNION ALL SELECT '가입 없이 바로 주문', '장바구니에 담고 버튼 한 번이면 주문이 끝나요.', '#/cart', '장바구니 가기', 3, NOW() - INTERVAL 3 DAY, NULL
) seed
WHERE NOT EXISTS (SELECT 1 FROM BANNER);

UPDATE NOTICE SET CONTENT = '홈 화면 ''최신 상품''에서 확인해 보세요.' WHERE CONTENT = '홈 화면 ''새로 들어온 상품''에서 확인해 보세요.';
