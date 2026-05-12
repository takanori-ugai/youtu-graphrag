package com.youtu.graphrag.shared.treecomm

import com.youtu.graphrag.shared.graph.GraphNode
import com.youtu.graphrag.shared.graph.GraphRelationship
import kotlin.test.Test
import kotlin.test.assertEquals

class FastTreeCommTest {
    @Test
    fun `detectCommunities counts unique nodes correctly`() {
        val nodeA = GraphNode("A", emptyMap())
        val nodeB = GraphNode("B", emptyMap())
        val nodeC = GraphNode("C", emptyMap())

        val relationships = listOf(
            GraphRelationship(nodeA, "links_to", nodeB),
            GraphRelationship(nodeB, "links_to", nodeC),
            GraphRelationship(nodeA, "links_to", nodeC)
        )

        val communities = FastTreeComm().detectCommunities(relationships)
        
        assertEquals(1, communities.size)
        assertEquals("community_1", communities[0]["community_id"])
        assertEquals(3, communities[0]["node_count"]) // 3 unique nodes: A, B, C
    }

    @Test
    fun `detectCommunities returns empty list for empty relationships`() {
        val communities = FastTreeComm().detectCommunities(emptyList())
        assertEquals(0, communities.size)
    }
}
