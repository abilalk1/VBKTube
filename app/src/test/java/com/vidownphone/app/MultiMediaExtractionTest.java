package com.vidownphone.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MultiMediaExtractionTest {

    @Test
    public void testInstagramCarouselDetection() {
        List<MediaFormatOption> options = new ArrayList<>();
        options.add(new MediaFormatOption("direct_url:https://ig.com/1.jpg", "Kaydırma #1 • HD Fotoğraf (JPG)", "Uploader • 1. Kaydırma", "jpg", 0, false, true));
        options.add(new MediaFormatOption("direct_url:https://ig.com/2.mp4", "Kaydırma #2 • Video (MP4)", "Uploader • 2. Kaydırma", "mp4", 0, false, true));
        options.add(new MediaFormatOption("direct_url:https://ig.com/3.jpg", "Kaydırma #3 • HD Fotoğraf (JPG)", "Uploader • 3. Kaydırma", "jpg", 0, false, true));

        AnalyzedMedia media = new AnalyzedMedia("https://instagram.com/p/ABC", "Test Post", "@tester", 0, "https://ig.com/1.jpg", options);
        assertTrue("Carousel post must be recognized as multi-media", media.isMultiMedia());
        assertEquals(3, media.getFormatOptions().size());
    }

    @Test
    public void testInstagramStoryDetection() {
        List<MediaFormatOption> options = new ArrayList<>();
        options.add(new MediaFormatOption("direct_url:https://ig.com/s1.mp4", "Hikaye #1 • Video (MP4)", "Uploader • 1. Hikaye", "mp4", 0, false, true));
        options.add(new MediaFormatOption("direct_url:https://ig.com/s2.jpg", "Hikaye #2 • Fotoğraf (JPG)", "Uploader • 2. Hikaye", "jpg", 0, false, true));

        AnalyzedMedia media = new AnalyzedMedia("https://instagram.com/stories/tester", "tester Hikayeleri (2)", "@tester", 0, "https://ig.com/s2.jpg", options, true);
        assertTrue("Instagram stories reel must be recognized as multi-media", media.isMultiMedia());
        assertEquals(2, media.getFormatOptions().size());
    }

    @Test
    public void testRedditGalleryDetection() {
        List<MediaFormatOption> options = new ArrayList<>();
        options.add(new MediaFormatOption("direct_url:https://i.redd.it/1.jpg", "Kaydırma #1 • HD Fotoğraf (JPG)", "r/pics • 1. Galeri Medyası", "jpg", 0, false, true));
        options.add(new MediaFormatOption("direct_url:https://i.redd.it/2.png", "Kaydırma #2 • HD Fotoğraf (PNG)", "r/pics • 2. Galeri Medyası", "png", 0, false, true));

        AnalyzedMedia media = new AnalyzedMedia("https://reddit.com/r/pics/comments/xyz", "Reddit Album", "r/pics", 0, "https://i.redd.it/1.jpg", options, true);
        assertTrue("Reddit gallery must be recognized as multi-media", media.isMultiMedia());
        assertEquals(2, media.getFormatOptions().size());
    }

    @Test
    public void testSingleVideoIsNotMultiMedia() {
        List<MediaFormatOption> options = new ArrayList<>();
        options.add(new MediaFormatOption("best", "1080p Full HD", "MP4 • 1080p", "mp4", 1000, false, false));
        options.add(new MediaFormatOption("720p", "720p HD", "MP4 • 720p", "mp4", 500, false, false));
        options.add(new MediaFormatOption("audio", "MP3 320kbps", "MP3", "mp3", 100, true, false));

        AnalyzedMedia media = new AnalyzedMedia("https://youtube.com/watch?v=123", "Single Video", "Creator", 120, "https://yt.com/thumb.jpg", options);
        assertFalse("Single video with multiple format choices must NOT be multi-media", media.isMultiMedia());
    }

    @Test
    public void testSubsetSelectionCalculation() {
        // User's specific scenario: 10 slides, wanting items 1, 3, 4, 7
        List<MediaFormatOption> allOptions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            allOptions.add(new MediaFormatOption("direct_url:https://ig.com/" + i + ".jpg", "Kaydırma #" + i + " • HD Fotoğraf (JPG)", "Uploader • " + i + ". Kaydırma", "jpg", 0, false, true));
        }

        // Indices for 1, 3, 4, 7 are 0, 2, 3, 6
        int[] userPickedIndices = {0, 2, 3, 6};
        List<MediaFormatOption> selectedSubset = new ArrayList<>();
        for (int idx : userPickedIndices) {
            selectedSubset.add(allOptions.get(idx));
        }

        assertEquals(4, selectedSubset.size());
        assertEquals("Kaydırma #1 • HD Fotoğraf (JPG)", selectedSubset.get(0).getLabel());
        assertEquals("Kaydırma #3 • HD Fotoğraf (JPG)", selectedSubset.get(1).getLabel());
        assertEquals("Kaydırma #4 • HD Fotoğraf (JPG)", selectedSubset.get(2).getLabel());
        assertEquals("Kaydırma #7 • HD Fotoğraf (JPG)", selectedSubset.get(3).getLabel());
    }

    @Test
    public void testInstagramStoryUrlParsing() {
        com.vidownphone.app.engine.SocialPlatformExtractor.InstagramStoryTarget target =
                com.vidownphone.app.engine.SocialPlatformExtractor.parseStoryOrHighlightUrl(
                        "https://www.instagram.com/stories/cristiano/3485729182374912384/?igsh=MW0yd2d");
        assertFalse("Should not be highlight", target.isHighlight);
        assertEquals("cristiano", target.username);
        assertEquals("3485729182374912384", target.targetStoryPk);
        assertEquals("https://www.instagram.com/stories/cristiano/3485729182374912384/", target.canonicalUrl);
    }

    @Test
    public void testInstagramHighlightWithStoryMediaIdParsing() {
        com.vidownphone.app.engine.SocialPlatformExtractor.InstagramStoryTarget target =
                com.vidownphone.app.engine.SocialPlatformExtractor.parseStoryOrHighlightUrl(
                        "https://www.instagram.com/stories/highlights/17987654321098765/?story_media_id=3485729182374912384");
        assertTrue("Should be highlight", target.isHighlight);
        assertEquals("17987654321098765", target.highlightId);
        assertEquals("3485729182374912384", target.targetStoryPk);
        assertEquals("https://www.instagram.com/stories/highlights/17987654321098765/?story_media_id=3485729182374912384", target.canonicalUrl);
    }

    @Test
    public void testUserExactHighlightLinks() {
        String link1 = "https://www.instagram.com/s/aGlnaGxpZ2h0OjE4MTU4ODQxNzE4MzkwMjI2?story_media_id=3663343479840436299_6463361857&exln=MWZoOHVocnNmNWdycg==";
        String link2 = "https://www.instagram.com/s/aGlnaGxpZ2h0OjE4MTU4ODQxNzE4MzkwMjI2?story_media_id=3663343479840436299_6463361857&rpxt=MWZoOHVocnNmNWdycg==";

        com.vidownphone.app.engine.SocialPlatformExtractor.InstagramStoryTarget t1 =
                com.vidownphone.app.engine.SocialPlatformExtractor.parseStoryOrHighlightUrl(link1);
        com.vidownphone.app.engine.SocialPlatformExtractor.InstagramStoryTarget t2 =
                com.vidownphone.app.engine.SocialPlatformExtractor.parseStoryOrHighlightUrl(link2);

        org.junit.Assert.assertNotNull("Target 1 must not be null", t1);
        org.junit.Assert.assertNotNull("Target 2 must not be null", t2);
        assertEquals("Highlight IDs must match", t1.highlightId, t2.highlightId);
        assertEquals("18158841718390226", t1.highlightId);
        assertEquals("Story PKs must match", t1.targetStoryPk, t2.targetStoryPk);
        assertEquals("3663343479840436299", t1.targetStoryPk);
        assertEquals("Canonical URLs must match", t1.canonicalUrl, t2.canonicalUrl);
        assertEquals("https://www.instagram.com/stories/highlights/18158841718390226/?story_media_id=3663343479840436299_6463361857", t1.canonicalUrl);
    }
}
