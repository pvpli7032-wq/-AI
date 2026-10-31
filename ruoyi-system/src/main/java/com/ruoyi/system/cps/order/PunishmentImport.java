package com.ruoyi.system.cps.order;

public class PunishmentImport {
    private Long id;
    private String platform;
    private String file;
    private Long operator;
    private int lines;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
    public Long getOperator() { return operator; }
    public void setOperator(Long operator) { this.operator = operator; }
    public int getLines() { return lines; }
    public void setLines(int lines) { this.lines = lines; }
}
