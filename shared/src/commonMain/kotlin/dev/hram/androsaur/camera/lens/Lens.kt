package dev.hram.androsaur.camera.lens

/** Which physical camera the service uses; the string form is what the API, MCP and settings carry. */
enum class Lens(val id: String) {
    BACK("back"), FRONT("front");

    companion object {
        /** The camera used when none was chosen: this project looks at people, so the one on the screen's side. */
        val DEFAULT = FRONT

        fun parse(id: String?): Lens? = entries.firstOrNull { it.id == id }
    }
}
