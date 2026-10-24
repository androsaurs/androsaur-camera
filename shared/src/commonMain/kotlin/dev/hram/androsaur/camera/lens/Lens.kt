package dev.hram.androsaur.camera.lens

/** Which physical camera the service uses; the string form is what the API, MCP and settings carry. */
enum class Lens(val id: String) {
    BACK("back"), FRONT("front");

    companion object {
        fun parse(id: String?): Lens? = entries.firstOrNull { it.id == id }
    }
}
