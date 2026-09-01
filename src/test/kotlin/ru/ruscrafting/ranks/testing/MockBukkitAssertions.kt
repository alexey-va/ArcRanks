package ru.ruscrafting.ranks.testing

/** Keeps existing test imports stable while using arc-core's shared fail-fast guard. */
fun <T> failOnUnsupportedMockBukkitOperation(block: () -> T): T =
    ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation(block)
