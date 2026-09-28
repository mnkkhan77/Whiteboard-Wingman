package com.mockinterview.backend.service;

import java.util.List;

/**
 * Realistic study-pack chunks (a textbook chapter on database transactions, ~chunk-sized like the
 * doc-processor's output) shared by the pack chat tests that run the real all-MiniLM-L6-v2 model.
 */
public final class PackChatFixtures {

    private PackChatFixtures() {
    }

    public static final List<String> TRANSACTIONS_CHAPTER = List.of(
            """
            ACID properties. A transaction is a unit of work that the database executes as a whole. \
            The ACID acronym names the four guarantees it provides: Atomicity means either all of the \
            transaction's writes take effect or none do; Consistency means a transaction moves the \
            database from one valid state to another, preserving constraints; Isolation means \
            concurrent transactions do not see each other's intermediate states; Durability means \
            that once a transaction commits, its changes survive crashes and power loss.""",
            """
            Isolation levels. The SQL standard defines four isolation levels, trading correctness for \
            concurrency. Read uncommitted allows dirty reads, where a transaction sees rows another \
            transaction wrote but has not committed. Read committed prevents dirty reads but allows \
            non-repeatable reads: reading the same row twice may return different values. Repeatable \
            read keeps rows stable for the whole transaction but may still allow phantom rows. \
            Serializable behaves as if transactions ran one after another.""",
            """
            Two-phase locking. Under two-phase locking (2PL) a transaction acquires shared locks for \
            reads and exclusive locks for writes. In the growing phase it may only acquire locks; once \
            it releases any lock it enters the shrinking phase and may not acquire new ones. Strict \
            2PL holds all exclusive locks until commit, which prevents other transactions from reading \
            uncommitted data and makes the schedule recoverable.""",
            """
            Deadlocks. When two transactions each hold a lock the other one needs, neither can proceed: \
            a deadlock. Databases detect deadlocks by building a waits-for graph and looking for \
            cycles; one transaction in the cycle is chosen as the victim and aborted so the others can \
            continue. Applications should be ready to retry a transaction that was aborted because of \
            a deadlock, and acquiring locks in a consistent order reduces how often they happen.""",
            """
            Multi-version concurrency control. MVCC keeps several versions of each row so that readers \
            never block writers and writers never block readers. Each transaction reads from a \
            snapshot taken when it started (or when each statement started, under read committed). \
            Old row versions that no running transaction can see any more are removed later by a \
            background cleanup process, called vacuum in PostgreSQL.""",
            """
            Write-ahead logging and recovery. Before a change is written to the data files, a record \
            describing it is appended to the write-ahead log (WAL) and flushed to disk. After a crash, \
            the database replays the log from the last checkpoint: committed transactions are redone \
            and uncommitted ones are undone. This is how durability is achieved without flushing every \
            modified data page at commit time.""");
}
