/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

interface IrAttributeContainer {
    // Any object which can be used as a key in the map in the backend-specific storage.
    // The idea is that this field should survive transformations and be copied when the corresponding element is copied.
    var attributeOwnerId: Any?
}

fun <D : IrAttributeContainer> D.copyAttributes(other: IrAttributeContainer?): D = apply {
    if (other != null) {
        attributeOwnerId = other.attributeOwnerId
    }
}
