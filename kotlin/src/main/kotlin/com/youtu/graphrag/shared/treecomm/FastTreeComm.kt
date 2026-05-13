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
        val adjacency = linkedMapOf<String, MutableSet<String>>()

        relationships.forEach { relationship ->
            val sourceId = nodeId(relationship.startNode)
            val targetId = nodeId(relationship.endNode)
            nodeById[sourceId] = relationship.startNode
            nodeById[targetId] = relationship.endNode
            adjacency.getOrPut(sourceId) { linkedSetOf() }.add(targetId)
            adjacency.getOrPut(targetId) { linkedSetOf() }.add(sourceId)
        }

        val communities = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val sortedNodeIds = nodeById.keys.sorted()

        sortedNodeIds.forEach { rootId ->
            if (!visited.add(rootId)) {
                return@forEach
            }

            val queue = ArrayDeque<String>()
            val members = mutableListOf<String>()
            queue.addLast(rootId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                members.add(current)
                adjacency[current].orEmpty().sorted().forEach { neighbor ->
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
                    nodeId(relationship.startNode) in memberSet &&
                        nodeId(relationship.endNode) in memberSet
                }

            mapOf(
                "community_id" to "community_${index + 1}",
                "node_count" to members.size,
                "edge_count" to edgeCount,
                "nodes" to members,
                "keywords" to extractKeywords(members),
            )
        }
    }

    private fun extractKeywords(members: List<String>): List<String> {
        val frequencies = mutableMapOf<String, Int>()
        members.forEach { member ->
            TOKEN_REGEX.findAll(member).forEach { match ->
                val token = match.value.lowercase(Locale.ROOT)
                if (token.length <= 2 || token in KEYWORD_STOPWORDS) {
                    return@forEach
                }
                frequencies[token] = (frequencies[token] ?: 0) + 1
            }
        }

        return frequencies.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key },
            ).take(MAX_KEYWORDS)
            .map { entry -> entry.key }
    }

    private fun nodeId(node: GraphNode): String = node.properties["name"]?.takeIf { it.isNotBlank() } ?: node.label

    companion object {
        private const val MAX_KEYWORDS = 5
        private val TOKEN_REGEX = Regex("[A-Za-z0-9_]+")
        private val KEYWORD_STOPWORDS =
            setOf(
                "and",
                "for",
                "from",
                "that",
                "the",
                "this",
                "with",
            )
    }
}
