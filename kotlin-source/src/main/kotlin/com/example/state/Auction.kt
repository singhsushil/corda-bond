package com.example.state

import com.example.flow.EndAuction
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant


data class Auction(
        val itemName: String,
        val ItemDescription: String,
        val capitalToBeRaised: Double,
        val allocation:String,
        val ExpiryDate: Instant,
        val itemOwner: Party,
        val AuctionActive: Boolean,
        val AuctionParticipants: List<Party>,
        val State: String,
        override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState, SchedulableState {
    override val participants: List<AbstractParty> = if (true) listOf(itemOwner) else listOf(itemOwner)

    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        return ScheduledActivity(flowLogicRefFactory.create(EndAuction::class.java,linearId.toString()), ExpiryDate)
    }
}