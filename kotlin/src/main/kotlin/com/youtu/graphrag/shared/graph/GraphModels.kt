package com.youtu.graphrag.shared.graph

data class GraphNode(
    val label: String,
    val properties: Map<String, String>,
)

data class GraphRelationship(
    val startNode: GraphNode,
    val relation: String,
    val endNode: GraphNode,
)
