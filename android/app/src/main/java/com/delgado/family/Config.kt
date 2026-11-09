package com.delgado.family

/** Everything family-specific in one place. Keep these in step with the dashboard and the Google script. */
object Config {
    const val DASHBOARD_URL = "https://kingsofleonxmail-debug.github.io/delgado-home-7x2khjo/family-dashboard.html"
    const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycby118t5CTyEQ6ycZvP72iZ0ashKqUJBekYhaJO0GrWB-53WgSK0Gj4n6MSppyMe3fGs3Q/exec"

    val KIDS = listOf("Julien", "Vinny")
    const val NAPPER = "Sebastian"
    const val MOM = "Mom"

    /** Bins: day of week (Calendar.SUNDAY = 1 … SATURDAY = 7) to what goes out. Morning pickups. */
    val BINS = listOf(2 to "Cardboard", 3 to "Plastics + Garbage")

    data class Workplace(val name: String, val address: String)

    /** Names must match WORK_LOCATIONS in the Google script exactly. */
    val WORKPLACES = listOf(
        Workplace("Wilkes-Barre", "1548 Sans Souci Pkwy, Wilkes-Barre, PA 18706"),
        Workplace("Kingston", "200 2nd Ave, Kingston, PA 18704")
    )

    /** How close counts as "arrived", in meters. Hospitals are big, so be generous. */
    const val GEOFENCE_RADIUS_M = 250f
}
