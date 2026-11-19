package com.likearr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LikearrTest {
    @Test void rejectsUnsafeComponents() {
        assertFalse(Likearr.safeComponent("../artist"));
        assertFalse(Likearr.safeComponent(".."));
        assertFalse(Likearr.safeComponent("evil\\name"));
        assertTrue(Likearr.safeComponent("Skrillex"));
    }
    @Test void matchesCaseAndWhitespace() {
        assertEquals(Likearr.norm("  M G M T "), Likearr.norm("m g m t"));
        assertEquals(Likearr.norm("Été"), Likearr.norm("E\u0301te\u0301"));
    }
    @Test void mapsJellyfinMusicPath() {
        assertEquals(Likearr.MUSIC.resolve("Explo/song.mp3"), Likearr.hostPath("/media/Music/Explo/song.mp3"));
        assertEquals(java.nio.file.Path.of("/other/song.mp3"), Likearr.hostPath("/other/song.mp3"));
    }
}
