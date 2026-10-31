package com.ruoyi.system.cps.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ruoyi.system.cps.rule.RuleDocuments.Draft;

/** Mutable drafts only; published history is append-only through RuleVersionMapper. */
@Mapper
public interface RuleMapper {
    @Insert("INSERT INTO cps_rule_document(platform,revision,config,archived,updated_by) VALUES(#{platform},1,#{config},0,#{operator})")
    int create(@Param("platform") String platform, @Param("config") String config, @Param("operator") long operator);

    @Select("SELECT id,platform,revision,config,archived FROM cps_rule_document WHERE platform=#{platform}")
    Draft byPlatform(String platform);

    @Select("SELECT id,platform,revision,config,archived FROM cps_rule_document WHERE id=#{id} FOR UPDATE")
    Draft lock(long id);

    @Select("SELECT id,platform,revision,config,archived FROM cps_rule_document ORDER BY id")
    List<Draft> list();

    @Update("UPDATE cps_rule_document SET config=#{config},revision=revision+1,archived=#{archived},updated_by=#{operator} WHERE id=#{id} AND revision=#{revision}")
    int replace(@Param("id") long id, @Param("revision") long revision, @Param("config") String config,
        @Param("archived") boolean archived, @Param("operator") long operator);
}
