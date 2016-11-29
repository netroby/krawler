package io.thelandscape.krawler.crawler.KrawlQueue

import com.github.andrewoma.kwery.core.Session
import com.github.andrewoma.kwery.fetcher.GraphFetcher
import com.github.andrewoma.kwery.fetcher.Node
import com.github.andrewoma.kwery.fetcher.Property
import com.github.andrewoma.kwery.fetcher.Type
import com.github.andrewoma.kwery.mapper.*
import com.github.andrewoma.kwery.mapper.util.camelToLowerUnderscore
import io.thelandscape.krawler.crawler.History.KrawlHistoryEntry
import io.thelandscape.krawler.crawler.History.krawlHistory
import io.thelandscape.krawler.hsqlSession

/**
 * Created by brian.a.madden@gmail.com on 10/31/16.
 *
 * Copyright (c) <2016> <H, llc>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

object historyConverter :
        SimpleConverter<KrawlHistoryEntry>( { row, c -> KrawlHistoryEntry(row.long(c)) }, KrawlHistoryEntry::id)

object krawlQueueTable : Table<QueueEntry, String>("krawlQueue",
        TableConfiguration(standardDefaults + timeDefaults + reifiedValue(KrawlHistoryEntry()),
                standardConverters + timeConverters + reifiedConverter(historyConverter), camelToLowerUnderscore)) {

    val Url by col(QueueEntry::url, id = true)
    val Parent by col (QueueEntry::parent)
    val Depth by col(QueueEntry::depth)
    val Timestamp by col(QueueEntry::timestamp)

    override fun idColumns(id: String) = setOf(Url of id)

    override fun create(value: Value<QueueEntry>) =
            QueueEntry(value of Url, value of Parent, value of Depth, value of Timestamp)

}

internal val KrawlQueueDao = KrawlQueueHSQLDao(hsqlSession)

class KrawlQueueHSQLDao(session: Session):
        KrawlQueueIf, AbstractDao<QueueEntry, String>(session, krawlQueueTable, QueueEntry::url) {

    init {
        // Create queue table
        session.update("CREATE TABLE IF NOT EXISTS krawlQueue " +
                "(url VARCHAR(255) NOT NULL PRIMARY KEY, parent INT, depth INT, timestamp TIMESTAMP)")
    }

    override fun pop(): QueueEntry? {

        val historyEntry = Type(KrawlHistoryEntry::id, { krawlHistory.findByIds(it) })
        val queueEntry = Type(QueueEntry::url, { this.pop(1) },
                Property(QueueEntry::parent,  historyEntry,
                        { it.parent.id }, { hist, parent -> hist.copy( parent = parent) })
        )

        val fetcher: GraphFetcher = GraphFetcher((setOf(historyEntry, queueEntry)))

        fun <T> Collection<T>.fetch(node: Node) = fetcher.fetch(this, Node(node))


        return pop(1).fetch(Node.all).firstOrNull()
    }

    private fun pop(n: Int): List<QueueEntry> {

        val selectSql = "SELECT TOP :n $columns FROM ${table.name}"
        val params = mapOf("n" to n)

        var out: List<QueueEntry> = listOf()
        session.transaction {
            out = session.select(selectSql, params, options("popMinDepth"), table.rowMapper())
            session.update("DELETE FROM ${table.name} WHERE 1 = 1 LIMIT $n")
        }

        return out
    }

    override fun push(urls: List<QueueEntry>): List<QueueEntry> {
        return this.batchInsert(urls)
    }
}