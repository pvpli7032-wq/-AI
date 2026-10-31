package com.ruoyi.system.cps.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.cps.order.PunishmentImport;
import com.ruoyi.system.cps.order.PunishmentLine;

/** Records only structured import data and authenticated operator; no credentials. */
public interface PunishmentEntryMapper {
    @Insert("INSERT INTO cps_punish_import(platform,file,operator,`lines`) VALUES (#{platform},#{file},#{operator},#{lines})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertImport(PunishmentImport record);

    @Insert("INSERT INTO cps_punish_import_item(import_id,line_no,order_no,sub_order_no,penalty_amount) VALUES(#{importId},#{lineNo},#{line.orderNo},#{line.subOrderNo},#{line.penaltyAmount})")
    int insertLine(@Param("importId") Long importId, @Param("lineNo") int lineNo, @Param("line") PunishmentLine line);
}
