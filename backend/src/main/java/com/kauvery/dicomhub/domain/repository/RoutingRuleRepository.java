package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.RoutingRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {
    List<RoutingRule> findBySiteIdAndActiveTrueOrderByPriorityOrderAsc(UUID siteId);
    List<RoutingRule> findBySiteIdOrderByPriorityOrderAsc(UUID siteId);
}
