package app.ecowidget.data

/** Les trois présentations du widget, au choix dans l'application (réglage global). */
enum class WidgetMode(val id: Int, val label: String, val hint: String) {
    CLASSIC(1, "Complet", "Le widget actuel : tout est affiché, un appui actualise."),
    TILES_PAGE(2, "Tuiles + page", "Tuiles compactes ; un appui ouvre une fiche détaillée en plein écran."),
    TILES_INLINE(3, "Tuiles + détail", "Tuiles compactes ; un appui affiche le détail dans le widget, qui revient aux tuiles après 30 s.");

    companion object {
        fun fromId(id: Int): WidgetMode = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}
