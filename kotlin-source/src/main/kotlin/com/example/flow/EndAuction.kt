package com.example.flow


import co.paralleluniverse.fibers.Suspendable
import com.example.contract.AuctionContract
import com.example.contract.BidContract
import com.example.state.Auction
import com.example.state.Bid
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.util.*;
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.utilities.seconds
import java.time.Instant
import kotlin.collections.ArrayList
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria.VaultQueryCriteria
import net.corda.core.utilities.unwrap


/**
 * This flow deals with ending the auction
 */
@SchedulableFlow
@InitiatingFlow
@StartableByRPC
class EndAuction(val AuctionReference: String) : FlowLogic<SignedTransaction>() {

    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
     * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object GENERATING_TRANSACTION : Step("Creating a new Auction.")
        object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
        object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        // Pick a notary. Don't care which one.
        val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

        // Stage 1.
        progressTracker.currentStep = GENERATING_TRANSACTION

        // Get the Auction state corresponding to the provided ID from our vault.
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(AuctionReference)))
        val auctionInputStateAndRef = serviceHub.vaultService.queryBy<Auction>(queryCriteria).states.single()
        val auctionState = auctionInputStateAndRef.state.data
        var auctionOutputState = auctionState.copy(AuctionActive = false)

        var bids = bookBuilding()
        var totalAmoutRaised = calculateTotalAmount(bids);

        if (totalAmoutRaised < auctionState.capitalToBeRaised) {
            auctionOutputState = auctionOutputState.copy(State = "FAIL")
        } else {
            auctionOutputState = auctionOutputState.copy(State = "SUCCESS")
        }
        val auctionOutputStateAndContract = StateAndContract(auctionOutputState, AuctionContract.CONTRACT_REF)

        val endAuctionCommand = Command(AuctionContract.End(), auctionState.itemOwner.owningKey)

        // Build, sign and record the transaction.
        val utx = TransactionBuilder(notary = notary).withItems(
                auctionOutputStateAndContract, // Output
                auctionInputStateAndRef, // Input
                endAuctionCommand  // Command
        )
        utx.setTimeWindow(Instant.now(), 30.seconds)
        //print(bids)
        val stx = serviceHub.signInitialTransaction(utx)
        val ftx = subFlow(FinalityFlow(stx))

        // Broadcast this transaction to all parties on this business network.
        subFlow(BroadcastTransaction(ftx, auctionState.AuctionParticipants))

        // update bidders state - need bidders sign ?



            println(" updating bids now ....")
            var allocation = createAllocation(bids, auctionState.capitalToBeRaised)
            for (item in allocation) {

                println(" all bids ..")
                println(item)
                println(item.bidder.owningKey)
                var status = "NOT ALLOTED"
                if(auctionOutputState.State == "SUCCESS"){
                    status = "ALLOTTED"
                }
                var weightedAverage = calculateWeightedAverage(allocation)
                val bidOutputState = Bid(item.amount, item.size, item.bidder, auctionState.itemOwner, UniqueIdentifier.fromString(AuctionReference), status,weightedAverage)
                val bitOutputStateAndContract = StateAndContract(bidOutputState, BidContract.CONTRACT_REF)
                val acceptBidCommand = Command(AuctionContract.AcceptBid(), auctionState.itemOwner.owningKey)
                val createbidCommand = Command(BidContract.Create(), listOf(item.bidder.owningKey, auctionState.itemOwner.owningKey))

                val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(AuctionReference)))
                val auctionInputStateAndRef = serviceHub.vaultService.queryBy<Auction>(queryCriteria).states.single()
                val auctionOutputState = auctionInputStateAndRef.state.data
                val auctionOutputStateAndContract = StateAndContract(auctionOutputState, AuctionContract.CONTRACT_REF)


                val utxBid = TransactionBuilder(notary = notary).withItems(
                        bitOutputStateAndContract, // Output
                        auctionOutputStateAndContract,
                        auctionInputStateAndRef,
                        createbidCommand,  // Command
                        acceptBidCommand
                )

                // Set the time for when this transaction happened.
                utxBid.setTimeWindow(Instant.now(), 30.seconds)
                println(" updating following bid ...bidder "+item.bidder+"  bidder owningKey "+item.bidder.owningKey+" owner owningKey "+auctionState.itemOwner)

                // Sign, sync identiStartties, finalise and record the transaction.
                val ptxBid = serviceHub.signInitialTransaction(builder = utxBid, signingPubKeys = listOf(ourIdentity.owningKey))
                val session = initiateFlow(item.bidder)
                subFlow(IdentitySyncFlow.Send(otherSide = session, tx = ptxBid.tx))
                val stxBid = subFlow(CollectSignaturesFlow(ptxBid, setOf(session), listOf(ourIdentity.owningKey)))
                val ftxBid = subFlow(FinalityFlow(stxBid))
                println(" happy ending...." )
            }
            return ftx
        }

        fun calculateTotalAmount(bids: ArrayList<Bid>): Double {
                var amount = 0.0
                for (bid in bids) {
                    amount += bid.amount * bid.size
                }
                return amount;
            }

            fun bookBuilding(): ArrayList<Bid> {
                val queryCriteria = VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                var result = serviceHub.vaultService.queryBy<Bid>(queryCriteria)

                val bids = result.states;
                val list = ArrayList<Bid>()
                for (bid in bids) {
                    list.add(bid.state.data)
                    logger.info("putting the bid state for " + bid.state.data.bidder.toString() + "in the map with the bid value" + bid.state.data.amount.toString() + bid.state.data.size.toString())

                }
                return list
        }
            fun calculateWeightedAverage(bids: ArrayList<Bid>):Double{
                var weightedPrice = 0.0;
                var totalSize = 0;
                for(a in bids){
                    weightedPrice = weightedPrice + a.amount*a.size
                    totalSize = totalSize + a.size
                }
                weightedPrice = weightedPrice /totalSize

                return weightedPrice
            }
            fun createAllocation(bids: ArrayList<Bid>, startCapital: Double): ArrayList<Bid> {

                val compareByPrice = { o1: Bid, o2: Bid -> o1.amount.compareTo(o2.amount) }
                var sortPrice = bids.stream().sorted(compareByPrice)
                var sortPriceConnter = 0
                var sortPriceAmount = 0.0

                var sortListPriceFiter = ArrayList<Bid>()
                for (b in sortPrice) {
                    sortPriceAmount = sortPriceAmount + b.amount * b.size
                    sortPriceConnter = sortPriceConnter + 1;

                    sortListPriceFiter.add(b)
                    if (sortPriceAmount >= startCapital)
                        break
                }


                return sortListPriceFiter;
                //Check if the sort on price meets the condition If yes return the sorted map

            }

    @InitiatedBy(EndAuction::class)
    class Responder(val otherSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(IdentitySyncFlow.Receive(otherSideSession = otherSession))

            // As the manager, we might want to do some checking of the bid before we sign it.
            val flow = object : SignTransactionFlow(otherSession) {
                override fun checkTransaction(stx: SignedTransaction) = Unit // TODO: Add some checks here.
            }

            val stx = subFlow(flow)
        }

    }

        }
