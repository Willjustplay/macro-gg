package com.example.data

/** Named child lists of container actions. */
enum class Slot(val label: String) {
    THEN("Jika ditemukan"),
    ELSE("Jika tidak ditemukan"),
    BODY("Isi perulangan")
}

/** Address of an action list: the root list ([parentId] == null) or a slot of a container action. */
data class ContainerRef(val parentId: String? = null, val slot: Slot? = null) {
    companion object {
        val ROOT = ContainerRef()
    }
}

fun Action.slots(): List<Slot> = when (this) {
    is ImageAction, is TrackObjectAction, is TextAction, is ColorAction -> listOf(Slot.THEN, Slot.ELSE)
    is LoopAction -> listOf(Slot.BODY)
    else -> emptyList()
}

fun Action.childList(slot: Slot): List<Action> = when (this) {
    is ImageAction -> if (slot == Slot.THEN) thenActions else if (slot == Slot.ELSE) elseActions else emptyList()
    is TrackObjectAction -> if (slot == Slot.THEN) thenActions else if (slot == Slot.ELSE) elseActions else emptyList()
    is TextAction -> if (slot == Slot.THEN) thenActions else if (slot == Slot.ELSE) elseActions else emptyList()
    is ColorAction -> if (slot == Slot.THEN) thenActions else if (slot == Slot.ELSE) elseActions else emptyList()
    is LoopAction -> if (slot == Slot.BODY) actions else emptyList()
    else -> emptyList()
}

fun Action.withChildList(slot: Slot, list: List<Action>): Action = when (this) {
    is ImageAction -> when (slot) {
        Slot.THEN -> copy(thenActions = list)
        Slot.ELSE -> copy(elseActions = list)
        else -> this
    }
    is TrackObjectAction -> when (slot) {
        Slot.THEN -> copy(thenActions = list)
        Slot.ELSE -> copy(elseActions = list)
        else -> this
    }
    is TextAction -> when (slot) {
        Slot.THEN -> copy(thenActions = list)
        Slot.ELSE -> copy(elseActions = list)
        else -> this
    }
    is ColorAction -> when (slot) {
        Slot.THEN -> copy(thenActions = list)
        Slot.ELSE -> copy(elseActions = list)
        else -> this
    }
    is LoopAction -> if (slot == Slot.BODY) copy(actions = list) else this
    else -> this
}

/** Applies [block] to every child list recursively, returning a rebuilt action. */
private fun Action.mapChildLists(block: (List<Action>) -> List<Action>): Action {
    var result = this
    for (slot in slots()) {
        result = result.withChildList(slot, block(result.childList(slot)))
    }
    return result
}

fun List<Action>.findAction(id: String): Action? {
    for (a in this) {
        if (a.id == id) return a
        for (slot in a.slots()) {
            a.childList(slot).findAction(id)?.let { return it }
        }
    }
    return null
}

fun List<Action>.updateAction(id: String, transform: (Action) -> Action): List<Action> = map { a ->
    if (a.id == id) transform(a) else a.mapChildLists { it.updateAction(id, transform) }
}

fun List<Action>.removeAction(id: String): List<Action> =
    filter { it.id != id }.map { a -> a.mapChildLists { it.removeAction(id) } }

fun List<Action>.moveAction(id: String, delta: Int): List<Action> {
    val idx = indexOfFirst { it.id == id }
    if (idx >= 0) {
        val target = (idx + delta).coerceIn(0, size - 1)
        if (target == idx) return this
        val list = toMutableList()
        val item = list.removeAt(idx)
        list.add(target, item)
        return list
    }
    return map { a -> a.mapChildLists { it.moveAction(id, delta) } }
}

/** Moves an action from [fromIndex] to [toIndex] inside the specified container (root or nested branch). */
fun List<Action>.reorderInContainer(ref: ContainerRef, fromIndex: Int, toIndex: Int): List<Action> {
    fun reorderList(list: List<Action>): List<Action> {
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }
    if (ref.parentId == null || ref.slot == null) return reorderList(this)
    return updateAction(ref.parentId) { parent ->
        parent.withChildList(ref.slot, reorderList(parent.childList(ref.slot)))
    }
}

fun List<Action>.duplicateAction(id: String): List<Action> {
    val idx = indexOfFirst { it.id == id }
    if (idx >= 0) {
        val list = toMutableList()
        list.add(idx + 1, listOf(this[idx]).cloneWithRemappedIds().first())
        return list
    }
    return map { a -> a.mapChildLists { it.duplicateAction(id) } }
}

/** Clones an action tree with fresh IDs while keeping internal [JumpAction.targetActionId] links intact. */
fun List<Action>.cloneWithRemappedIds(idGenerator: () -> String = { newId() }): List<Action> {
    if (isEmpty()) return this
    val idMap = HashMap<String, String>()
    for (a in walk()) {
        idMap[a.id] = idGenerator()
    }
    return map { it.withMappedIds(idMap) }
}

fun List<Action>.insertInto(ref: ContainerRef, action: Action): List<Action> {
    if (ref.parentId == null || ref.slot == null) return this + action
    return updateAction(ref.parentId) { parent ->
        parent.withChildList(ref.slot, parent.childList(ref.slot) + action)
    }
}

/** Depth-first walk over every action in the tree. */
fun List<Action>.walk(): Sequence<Action> = sequence {
    for (a in this@walk) {
        yield(a)
        for (slot in a.slots()) yieldAll(a.childList(slot).walk())
    }
}

/** Depth-first walk carrying the immediate enclosing branch [Slot] (null for top-level actions). */
fun List<Action>.walkWithSlot(enclosing: Slot? = null): Sequence<Pair<Action, Slot?>> = sequence {
    for (a in this@walkWithSlot) {
        yield(a to enclosing)
        for (slot in a.slots()) yieldAll(a.childList(slot).walkWithSlot(slot))
    }
}

/** Recursively transforms every action in the tree. */
fun List<Action>.mapAllActions(transform: (Action) -> Action): List<Action> = map { a ->
    val mappedChildren = a.mapChildLists { it.mapAllActions(transform) }
    transform(mappedChildren)
}

/** Returns the action list for [branchId] (empty string = Jalur Utama). */
fun Macro.actionsForBranch(branchId: String): List<Action> =
    if (branchId.isBlank()) actions else branches.firstOrNull { it.id == branchId }?.actions.orEmpty()

/** Returns a human-readable label for [branchId] ("Jalur Utama" when blank). */
fun Macro.branchDisplayName(branchId: String): String =
    if (branchId.isBlank()) "Jalur Utama"
    else branches.firstOrNull { it.id == branchId }?.name?.ifBlank { "Cabang" } ?: "Jalur Utama"

/** Returns a copy of this [Macro] with the actions of [branchId] (empty = Jalur Utama) transformed. */
fun Macro.updateBranchActions(branchId: String, transform: (List<Action>) -> List<Action>): Macro =
    if (branchId.isBlank()) {
        copy(actions = transform(actions))
    } else {
        copy(branches = branches.map { b -> if (b.id == branchId) b.copy(actions = transform(b.actions)) else b })
    }

/** Depth-first walk over main [Macro.actions], all named [Macro.branches], and out-of-band [Macro.stuckActions]. */
fun Macro.allActionsSequence(): Sequence<Action> = sequence {
    yieldAll(actions.walk())
    for (b in branches) {
        yieldAll(b.actions.walk())
    }
    yieldAll(stuckActions.walk())
}

fun Macro.findAnyAction(id: String): Action? =
    actions.findAction(id)
        ?: branches.firstNotNullOfOrNull { it.actions.findAction(id) }
        ?: stuckActions.findAction(id)

fun Macro.isStuckAction(id: String): Boolean =
    stuckActions.findAction(id) != null

/** Returns the top-level action list that owns [actionId] (main, a named branch, or stuckActions). */
fun Macro.ownerListForAction(actionId: String): List<Action> = when {
    actions.findAction(actionId) != null -> actions
    stuckActions.findAction(actionId) != null -> stuckActions
    else -> branches.firstOrNull { it.actions.findAction(actionId) != null }?.actions ?: actions
}

fun Macro.updateAnyAction(id: String, transform: (Action) -> Action): Macro = when {
    actions.findAction(id) != null -> copy(actions = actions.updateAction(id, transform))
    branches.any { it.actions.findAction(id) != null } -> copy(
        branches = branches.map { b ->
            if (b.actions.findAction(id) != null) b.copy(actions = b.actions.updateAction(id, transform)) else b
        }
    )
    stuckActions.findAction(id) != null -> copy(stuckActions = stuckActions.updateAction(id, transform))
    else -> this
}

fun Macro.removeAnyAction(id: String): Macro = when {
    actions.findAction(id) != null -> copy(actions = actions.removeAction(id))
    branches.any { it.actions.findAction(id) != null } -> copy(
        branches = branches.map { b ->
            if (b.actions.findAction(id) != null) b.copy(actions = b.actions.removeAction(id)) else b
        }
    )
    stuckActions.findAction(id) != null -> copy(stuckActions = stuckActions.removeAction(id))
    else -> this
}

fun Macro.moveAnyAction(id: String, delta: Int): Macro = when {
    actions.findAction(id) != null -> copy(actions = actions.moveAction(id, delta))
    branches.any { it.actions.findAction(id) != null } -> copy(
        branches = branches.map { b ->
            if (b.actions.findAction(id) != null) b.copy(actions = b.actions.moveAction(id, delta)) else b
        }
    )
    stuckActions.findAction(id) != null -> copy(stuckActions = stuckActions.moveAction(id, delta))
    else -> this
}

/** Returns "" if [actionId] belongs to Jalur Utama, the [MacroBranch.id] if in a branch, or null if in stuckActions / not found. */
fun Macro.branchIdOfAction(actionId: String): String? = when {
    actions.findAction(actionId) != null -> ""
    else -> branches.firstOrNull { it.actions.findAction(actionId) != null }?.id
}

/** Moves [actionId] out of its current branch (or Jalur Utama) into [targetBranchId] (empty string = Jalur Utama). */
fun Macro.moveActionToBranch(actionId: String, targetBranchId: String): Macro {
    val found = findAnyAction(actionId) ?: return this
    val cleanTarget = targetBranchId.trim()
    if (cleanTarget.isNotBlank() && branches.none { it.id == cleanTarget }) return this
    val currentBranch = branchIdOfAction(actionId)
    if (currentBranch != null && currentBranch == cleanTarget &&
        actionsForBranch(cleanTarget).any { it.id == actionId }
    ) {
        return this
    }
    val removed = removeAnyAction(actionId)
    return removed.updateBranchActions(cleanTarget) { it + found }
}

fun Macro.imageNames(): Set<String> = buildSet {
    bannedImages.filter { it.isNotBlank() }.forEach { add(it) }
    for (a in allActionsSequence()) {
        when (a) {
            is ImageAction -> {
                if (a.imageName.isNotBlank()) add(a.imageName)
                a.bannedImages.filter { it.isNotBlank() }.forEach { add(it) }
            }
            is TrackObjectAction -> a.allTemplateNames().forEach { add(it) }
            is BranchRouterAction -> {
                a.rules.forEach { r ->
                    if (r.conditionType == BranchConditionType.IMAGE && r.imageName.isNotBlank()) {
                        add(r.imageName)
                    }
                }
            }
            else -> Unit
        }
    }
}

fun Macro.countActions(): Int = actions.walk().count() + branches.sumOf { it.actions.walk().count() }

fun Macro.countStuckActions(): Int = stuckActions.walk().count()
