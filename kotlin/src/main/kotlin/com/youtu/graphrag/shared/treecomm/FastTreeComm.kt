package com.youtu.graphrag.shared.treecomm

import com.youtu.graphrag.shared.graph.GraphNode
import com.youtu.graphrag.shared.graph.GraphRelationship
import java.util.Locale

class FastTreeComm {
    fun detectCommunities(relationships: List<GraphRelationship>): List<Map<String, Any>> {
        if (relationships.isEmpty()) {
            return emptyList()
        }

        val nodeById = linkedMapOf<String, GraphNode>()
        val adjacency = linkedMapOf<String, MutableMap<String, Int>>()

        relationships.forEach { relationship ->
            val sourceId = nodeId(relationship.startNode)
            val targetId = nodeId(relationship.endNode)
            if (sourceId.isBlank() || targetId.isBlank()) {
                return@forEach
            }

            nodeById[sourceId] = relationship.startNode
            nodeById[targetId] = relationship.endNode
            adjacency
                .getOrPut(sourceId) { linkedMapOf() }
                .merge(targetId, 1, Int::plus)
            adjacency
                .getOrPut(targetId) { linkedMapOf() }
                .merge(sourceId, 1, Int::plus)
        }
        if (nodeById.isEmpty()) {
            return emptyList()
        }

        val communities = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        nodeById.keys.sorted().forEach { rootId ->
            if (!visited.add(rootId)) {
                return@forEach
            }

            val queue = ArrayDeque<String>()
            val members = mutableListOf<String>()
            queue.addLast(rootId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                members.add(current)
                adjacency[current]
                    .orEmpty()
                    .keys
                    .sorted()
                    .forEach { neighbor ->
                        if (visited.add(neighbor)) {
                            queue.addLast(neighbor)
                        }
                    }
            }
            communities.add(members.sorted())
        }

        val sortedCommunities =
            communities.sortedWith(
                compareByDescending<List<String>> { it.size }
                    .thenBy { it.firstOrNull().orEmpty() },
            )

        return sortedCommunities.mapIndexed { index, members ->
            val memberSet = members.toSet()
            val edgeCount =
                relationships.count { relationship ->
                    val sourceId = nodeId(relationship.startNode)
                    val targetId = nodeId(relationship.endNode)
                    sourceId in memberSet && targetId in memberSet
                }

            mapOf(
                "community_id" to "community_${index + 1}",
                "node_count" to members.size,
                "edge_count" to edgeCount,
                "nodes" to members,
                "keywords" to extractRepresentativeMembers(members, adjacency),
            )
        }
    }

    private fun extractRepresentativeMembers(
        members: List<String>,
        adjacency: Map<String, Map<String, Int>>,
    ): List<String> {
        if (members.isEmpty()) {
            return emptyList()
        }

        val memberSet = members.toSet()
        return members
            .map { member ->
                val degree =
                    adjacency[member]
                        .orEmpty()
                        .filterKeys { neighbor -> neighbor in memberSet }
                        .values
                        .sum()
                member to degree
            }.sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { it.first.lowercase(Locale.ROOT) },
            ).take(MAX_KEYWORDS)
            .map { pair -> pair.first }
    }

    private fun nodeId(node: GraphNode): String {
        val fromName = node.properties["name"]?.toString()?.trim().orEmpty()
        return fromName.ifBlank { node.label.trim() }
    }

    companion object {
        private const val MAX_KEYWORDS = 5
    }
}
