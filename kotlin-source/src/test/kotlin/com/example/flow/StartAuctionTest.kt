package com.example.flow

import net.corda.core.utilities.getOrThrow
import com.example.state.Auction
import org.junit.Test
import kotlin.test.assertEquals

class StartAuctionTests : AuctionTest() {
    private val johnsAuction
        get() = Auction(
            itemName = "test",
            ItemDescription = "test",
            capitalToBeRaised = 100.0,

            State = "",
            ExpiryDate = fiveSecondsFromNow,
            itemOwner = a.legalIdentity(),
            AuctionActive = true,
            allocation = "",
            AuctionParticipants = listOf(b.legalIdentity(),c.legalIdentity())
    )

    private val janesAuction
        get() = Auction(
            itemName = "test2",
            ItemDescription = "test2",
            capitalToBeRaised = 100.0,
            State = "",
            ExpiryDate = fiveSecondsFromNow,
            itemOwner = a.legalIdentity(),
            AuctionActive = true,
            allocation = "",
            AuctionParticipants = listOf()
        )


    @Test
    fun `successfully start and broadcast to selected nodes`() {
        // Start a new Campaign.
        val flow = StartAuction(johnsAuction.itemName,johnsAuction.ItemDescription,johnsAuction.capitalToBeRaised,johnsAuction.allocation,johnsAuction.ExpiryDate.toString(),johnsAuction.AuctionParticipants.joinToString(separator = "$"))
        val auction = a.startFlow(flow).getOrThrow()

        // Extract the state from the transaction.
        val auctionStateRef = auction.tx.outRef<Auction>(0).ref
        val auctionState = auction.tx.outputs.single()

        // Get the Campaign state from the observer node vaults.
        val aAuction = a.transaction { a.services.loadState(auctionStateRef) }
        val bAuction = b.transaction { b.services.loadState(auctionStateRef) }
        val cAuction = c.transaction { c.services.loadState(auctionStateRef) }
        val dAuction = d.transaction { d.services.loadState(auctionStateRef) }
        val eAuction = e.transaction { e.services.loadState(auctionStateRef) }

        // All the states should be equal.
        assertEquals(1, setOf(auctionState, aAuction, bAuction, cAuction).size)
        assertEquals(0, setOf(auctionState, dAuction, eAuction).size)

        // We just shut down the nodes now - no need to wait for the nextScheduledActivity.
    }


    @Test
    fun `successfully start and broadcast campaign to all nodes`() {
        // Start a new Campaign.
        val flow = StartAuction(janesAuction.itemName,janesAuction.ItemDescription,janesAuction.capitalToBeRaised,janesAuction.allocation,janesAuction.ExpiryDate.toString(),"None")
        val auction = a.startFlow(flow).getOrThrow()

        // Extract the state from the transaction.
        val auctionStateRef = auction.tx.outRef<Auction>(0).ref
        val auctionState = auction.tx.outputs.single()

        // Get the Campaign state from the observer node vaults.
        val aAuction = a.transaction { a.services.loadState(auctionStateRef) }
        val bAuction = b.transaction { b.services.loadState(auctionStateRef) }
        val cAuction = c.transaction { c.services.loadState(auctionStateRef) }
        val dAuction = d.transaction { d.services.loadState(auctionStateRef) }
        val eAuction = e.transaction { e.services.loadState(auctionStateRef) }

        // All the states should be equal.
        assertEquals(1, setOf(auctionState, aAuction, bAuction, cAuction, dAuction, eAuction).size)

        // We just shut down the nodes now - no need to wait for the nextScheduledActivity.
    }

}