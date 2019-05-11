package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.AuctionContract
import com.example.contract.BidContract
import com.example.state.Auction
import com.example.state.Bid
import net.corda.confidential.IdentitySyncFlow
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
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import java.time.Instant

object EndMyAuction {

    /**
     * Takes an amount of currency and a auction reference then creates a new bid state and updates the existing
     * auction state to reflect the new bid.
     */
    @StartableByRPC
    @InitiatingFlow
    class InitiatiateEndFlow(
            val AuctionReference: String
    ) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Creating a new Auction.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
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



            // Get the auction state corresponding to the provided ID from our vault.
            // Stage 1.
            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(AuctionReference)))
            val auctionInputStateAndRef = serviceHub.vaultService.queryBy<Auction>(queryCriteria).states.single()
            val auctionState = auctionInputStateAndRef.state.data

            // Assemble the other transaction components.
            // Commands:
            val acceptAuctionCommand = Command(AuctionContract.AcceptBid(), auctionState.itemOwner.owningKey)
            val createAuctionCommand = Command(BidContract.Create(), listOf(ourIdentity.owningKey, auctionState.itemOwner.owningKey))
            var bids = bookBuilding()

            var totalAmoutRaised = calculateTotalAmount(bids);
            var auctionOutputState = auctionState.copy(AuctionActive = false)
            if (totalAmoutRaised < auctionState.capitalToBeRaised) {
                println("Could not raise the capital.Please re ccreate the auction")
                auctionOutputState = auctionOutputState.copy(State = "FAIL")
            } else {
                println("Congrats Capital has been raised")
                auctionOutputState = auctionOutputState.copy(State = "SUCCESS")
            }
            val auctionOutputStateAndContract = StateAndContract(auctionOutputState, AuctionContract.CONTRACT_REF)
            val endAuctionCommand = Command(AuctionContract.End(), auctionState.itemOwner.owningKey)
            val utxAuction = TransactionBuilder(notary = notary).withItems(
                    auctionOutputStateAndContract, // Output
                    auctionInputStateAndRef, // Input
                    endAuctionCommand  // Command
            )
            val stxAuction = serviceHub.signInitialTransaction(utxAuction)
            val ftxAuction = subFlow(FinalityFlow(stxAuction))
            // Broadcast this transaction to all parties on this business network.
            subFlow(BroadcastTransaction(ftxAuction , auctionState.AuctionParticipants))


            // Output states:
            if(auctionOutputState.State == "SUCCESS"){
                println("Starting the allocation process")
                var allocation = createAllocation(bids, auctionState.capitalToBeRaised)
                for (item in allocation) {
                    val bidOutputState = Bid(item.amount, item.size, serviceHub.myInfo.legalIdentities.first(), auctionState.itemOwner, UniqueIdentifier.fromString(AuctionReference), "",0.0)
                    val bitOutputStateAndContract = StateAndContract(bidOutputState, BidContract.CONTRACT_REF)

                    val queryCriteriaLatest = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(AuctionReference)))
                    val auctionInputStateAndRefLatest = serviceHub.vaultService.queryBy<Auction>(queryCriteria).states.single()
                    val auctionStateLatest = auctionInputStateAndRef.state.data

                    val auctionOutputStateAndContractLatest = StateAndContract(auctionStateLatest, AuctionContract.CONTRACT_REF)
                    // Build the transaction.
                    println("build the transaction")
                    val utx = TransactionBuilder(notary = notary).withItems(
                            bitOutputStateAndContract, // Output
                            auctionOutputStateAndContractLatest, // Output
                            auctionInputStateAndRefLatest, // Input
                            acceptAuctionCommand, // Command
                            createAuctionCommand  // Command
                    )

                    // Set the time for when this transaction happened.
                    utx.setTimeWindow(Instant.now(), 30.seconds)
                    println("Sign, sync identiStartties, finalise and record the transaction")
                    // Sign, sync identiStartties, finalise and record the transaction.
                    val ptx = serviceHub.signInitialTransaction(builder = utx, signingPubKeys = listOf(ourIdentity.owningKey))
                    println("initiate a session")
                    val session = initiateFlow(item.bidder)
                    println("initiate a IdentitySyncFlow")
                    subFlow(IdentitySyncFlow.Send(otherSide = session, tx = ptx.tx))
                    println("initiate a collect signature")
                    val stx = subFlow(CollectSignaturesFlow(ptx, setOf(session), listOf(ourIdentity.owningKey)))
                    println("finality flow")
                    val ftx = subFlow(FinalityFlow(stx))

                    // Send list of auction paricipants to broadcast transaction
                    session.sendAndReceive<Unit>(auctionState.AuctionParticipants)
                    println("no error end here ")
                }


            }
            return ftxAuction
        }

        fun calculateTotalAmount(bids: ArrayList<Bid>): Double {
            var amount = 0.0
            for (bid in bids) {
                amount = bid.amount * bid.size
            }
            return amount;
        }

        fun bookBuilding(): ArrayList<Bid> {

            var result = serviceHub.vaultService.queryBy<Bid>()

            val bids = result.states;
            val list = ArrayList<Bid>()
            for (bid in bids) {
                list.add(bid.state.data)
                logger.info("putting the bid state for " + bid.state.data.bidder.toString() + "in the map with the bid value" + bid.state.data.amount.toString() + bid.state.data.size.toString())

            }
            return list
        }

        fun createAllocation(bids: ArrayList<Bid>, startCapital: Double): ArrayList<Bid> {

            val compareByPrice = { o1: Bid, o2: Bid -> o1.amount.compareTo(o2.amount) }
            val compareByLot = { o1: Bid, o2: Bid -> o1.size.compareTo(o2.size) }

            var sortLot = bids.stream().sorted(compareByLot)

            //var sortLotConnter = 0.0
            //var sortLotAmount = 0.0
            var sortListFiter = ArrayList<Bid>()
            //for (b in sortLot) {
              //  sortLotAmount = sortLotAmount + b.amount * b.size
                //sortLotConnter = sortLotConnter + 1;
                //sortListFiter.add(b)
                //if (sortLotAmount >= startCapital)
                  //  break
            //}
            var sortPrice = bids.stream().sorted(compareByPrice)
            var sortPriceConnter = 0
            var sortPriceAmount = 0.0
            var sortListPriceFiter = ArrayList<Bid>()
            for (b in sortPrice) {
                sortPriceAmount = sortPriceAmount + b.amount * b.size
                sortPriceConnter = sortPriceConnter + 1;
                sortListPriceFiter.add(b)
                          }


            return sortListFiter;
            //Check if the sort on price meets the condition If yes return the sorted map

        }


    }

    /**
     * This side is only run by the auction creator who checks the bid then waits for the bid
     * transaction to be committed and broadcasts it to all the parties on the business network.
     */
    @InitiatedBy(InitiatiateEndFlow::class)
    class Responder(val otherSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(IdentitySyncFlow.Receive(otherSideSession = otherSession))

            // As the manager, we might want to do some checking of the bid before we sign it.
            val flow = object : SignTransactionFlow(otherSession) {
                override fun checkTransaction(stx: SignedTransaction) = Unit // TODO: Add some checks here.
            }

            val stx = subFlow(flow)

            // Once the transaction has been committed then we then broadcast from the manager.
            val AuctionParticipants = otherSession.receive<List<Party>>().unwrap { it }
            val ftx = waitForLedgerCommit(stx.id)
            subFlow(BroadcastTransaction(ftx, AuctionParticipants))

            // We want the other side to block or at least wait a while for the transaction to be broadcast.
            otherSession.send(Unit)
        }

    }
}

