package com.plexiti.horizon.query

import com.plexiti.horizon.domain.AccountCreated
import org.axonframework.eventhandling.EventHandler
import org.axonframework.queryhandling.QueryHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import javax.persistence.Entity
import javax.persistence.EntityManager
import javax.persistence.Id

/**
 * @author Martin Schimak <martin.schimak@plexiti.com>
 */
@Entity
data class AccountSummary(@Id val id: String, val name: String, val balance: Float)

@Component
class AccountProjection(private val entityManager: EntityManager) {

    private val logger = LoggerFactory.getLogger(AccountProjection::class.java)

    @EventHandler
    fun on(event: AccountCreated) {
        logger.debug(event.toString())
        entityManager.persist(AccountSummary(event.accountId.id, event.name, 0F))
    }

    @QueryHandler
    fun process(query: CheckBalance): AccountSummary {
        logger.debug(query.toString())
        return entityManager.createQuery("select a from AccountSummary a where a.name=:account")
            .setParameter("account", query.name)
            .singleResult as AccountSummary
    }

}

data class CheckBalance(val name: String)