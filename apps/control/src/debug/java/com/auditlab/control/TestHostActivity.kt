package com.auditlab.control

import androidx.activity.ComponentActivity

/**
 * Actividad vacía que solo existe en la variante debug para que Robolectric pueda componer las
 * pantallas en la JVM. No está exportada y no aporta comportamiento al producto.
 */
class TestHostActivity : ComponentActivity()
