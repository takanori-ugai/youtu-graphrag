package com.youtu.graphrag.shared.treecomm

import com.youtu.graphrag.shared.graph.GraphRelationship

class FastTreeComm {
    fun detectCommunities(relationships: List<GraphRelationship>): List<Map<String, Any>> {
        if (relationships.isEmpty()) {
            return emptyList()
        }
        return listOf(
            mapOf(
                "community_id" to "community_1",
                "node_count" to relationships.size,
            ),
        )
    }
}
