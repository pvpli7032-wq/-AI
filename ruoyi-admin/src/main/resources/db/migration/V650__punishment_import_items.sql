-- 每行留存具体处罚内容，原上传文件名只作元数据；历史导入记录保持不变。
CREATE TABLE cps_punish_import_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    order_no VARCHAR(100) NOT NULL,
    sub_order_no VARCHAR(100) NOT NULL DEFAULT '',
    penalty_amount DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_punish_import_line (import_id, line_no),
    CONSTRAINT fk_punish_item_import FOREIGN KEY (import_id) REFERENCES cps_punish_import(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
