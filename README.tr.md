<p align="center">
  <img src="app/src/main/res/drawable/ic_splash_logo.png" width="112" alt="VBKTube logosu" />
</p>

<h1 align="center">VBKTube</h1>

<p align="center">
  YouTube, Instagram, TikTok, X, Reddit ve 1000'den fazla siteden video, ses ve görsel indiren açık kaynaklı Android uygulaması.
</p>

<p align="center">
  <a href="https://github.com/abilalk1/VBKTube/releases/latest"><img src="https://img.shields.io/github/v/release/abilalk1/VBKTube?label=APK%20%C4%B0ndir&style=for-the-badge" alt="APK İndir" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0" />
  <img src="https://img.shields.io/badge/language-Java%2017-orange" alt="Java 17" />
</p>

<p align="center">
  <a href="README.md">🇬🇧 English</a> | 🇹🇷 Türkçe
</p>

---

## Ekran Görüntüleri

<p align="center">
  <img src="docs/screenshots/home.png" width="200" alt="Ana ekran" />
  <img src="docs/screenshots/formats.png" width="200" alt="Kalite seçimi" />
  <img src="docs/screenshots/photo.png" width="200" alt="Fotoğraf indirme" />
  <img src="docs/screenshots/downloads.png" width="200" alt="İndirme yöneticisi" />
  <img src="docs/screenshots/browser.png" width="200" alt="Dahili tarayıcı" />
</p>

## Özellikler

**İndirme**
- **Bağlantıyı yapıştır, indir.** Kaliteyi ve biçimi seç ya da tek dokunuşla hızlı indir.
- **Uygulamadan çıkmadan indir.** YouTube, Instagram, TikTok, X veya başka bir uygulamada Paylaş'a dokun ve VBKTube'u seç. O uygulamanın üstünde küçük bir panel açılır; kaliteyi (ya da istediğin kareleri) seçip indirmeyi başlatırsın. VBKTube açılmaz, kaldığın yerde kalırsın.
- **Video, ses veya görsel.** Kaynağın gerçekten sunduğu kalitelerde MP4, MP3 veya orijinal M4A ses ve JPG/PNG/WebP/GIF görseller (Google Görseller bağlantıları dahil).
- **Çoklu seçim.** Albümlerden, galerilerden ve hikâye gruplarından sadece istediğin kareleri seç.
- **Birçok site, büyük platformlara özel destekle:**
  - **YouTube:** videolar ve Shorts; ayrı gelen video ve ses akışları senin için birleştirilir.
  - **Instagram:** gönderiler, Reels, albümler, hikâyeler ve öne çıkanlar. Müzikli gönderiler müzikle birlikte video olarak ya da sadece ses olarak kaydedilebilir. Hikâyeler için dahili tarayıcıda bir kez giriş yapmak gerekir.
  - **X / Twitter:** HD video ve GIF.
  - **TikTok:** filigransız HD ve sesi MP3 olarak.
  - **Reddit:** videolar (video ve ses tek MP4'te birleştirilir) ve görsel galerileri.
  - **Facebook, Vimeo ve yt-dlp sayesinde 1000'den fazla site daha.**
- **Akışlar ve sıradışı bağlantılar.** `.m3u8` (HLS) akışları MP4'e çevrilir. Uzantısız bağlantılar içerik türüne göre kontrol edilir, gömülü videosu olan sayfalar taranır.

**Dahili tarayıcı**
- Reklam engelleme ve sayfada oynayan video ve görselleri listeleyen medya algılayıcı; gördüğünü indirebilirsin.
- Bir siteye bir kez giriş yap, erişimin olan özel ya da girişle açılan içerikleri VBKTube indirebilsin.
- Tam ekran video ve favori siteler için kendi kısayolların.

**İndirme yöneticisi**
- İndirmeler arka planda, aynı anda ikişer tane çalışır; ilerleme bildirimi ve bitince ya da hata olunca ayrı bir bildirim gelir.
- İptal, yeniden deneme, dahili oynatıcıda oynatma, paylaşma ve silme (dosyayla birlikte ya da sadece kayıt).
- Biten dosyalar galeriye ve müzik kütüphanene, `VBKTube` klasörüne kaydedilir.
- Bir şey ters gidince anlaşılır mesajlar (internet yok, bağlantının süresi dolmuş, yer yok, giriş gerekli...) ve uygulama kapanınca yarım kalan indirmeler bu şekilde işaretlenir.

**Uygulama**
- Koyu, açık ya da sistem teması. İngilizce ve Türkçe.
- GitHub Releases üzerinden güncelleme kontrolü (kapatılabilir) ve yt-dlp motorunu uygulama içinden güncelleme düğmesi. yt-dlp için Nightly ya da Stable kanalı seçilebilir.
- Reklam yok, takip yok, hesap yok. [Gizlilik](#gizlilik) bölümüne bak.

## Kurulum

1. [Releases](https://github.com/abilalk1/VBKTube/releases/latest) sayfasından en son `app-universal-release.apk` dosyasını (ya da işlemcine uygun olanı, genellikle `arm64-v8a`) indir.
2. Dosyayı telefonunda aç ve Android sorduğunda bu kaynaktan yüklemeye izin ver.
3. VBKTube'u aç, bir bağlantı yapıştır ve **Kalite ve Biçim Seç**'e dokun.

VBKTube, Google Play'de yer almaz, çünkü Play, YouTube'dan indirme yapan uygulamalara izin vermez.

## Kaynaktan derleme

Gereksinimler: Android Studio (ya da JDK 17) ve Android SDK.

```bash
git clone https://github.com/abilalk1/VBKTube.git
cd VBKTube
./gradlew assembleDebug
```

APK dosyaları `app/build/outputs/apk/debug/` klasörüne yazılır.

Projeyi Android Studio ile açarsan Android SDK yolu otomatik ayarlanır. Komut satırından derlerken `sdk.dir=/Android/Sdk/yolu` satırını içeren bir `local.properties` dosyası oluştur (ya da `ANDROID_HOME` değişkenini ayarla).

## Nasıl çalışır?

Her bağlantı, biri başarılı olana kadar şu adımlardan geçer:

1. **Doğrudan medya:** `.mp4`, `.mp3`, `.m3u8`, görseller vb. bağlantılar. Uzantısız bağlantılar içerik türüne göre kontrol edilir.
2. **Platform çıkarıcıları:** YouTube için NewPipeExtractor; Instagram, X, TikTok ve Reddit için özel çıkarıcılar.
3. **yt-dlp:** 1000'den fazla site için evrensel yedek yöntem. FFmpeg, ayrı video ve ses akışlarını birleştirir.
4. **Sayfa taraması:** Sayfadaki gömülü videoyu arar (Open Graph, `<video>`, JSON-LD).

## Gizlilik

VBKTube'da reklam, analiz ve hesap yoktur; geliştiriciye hiçbir şey göndermez. Yine de bağlantılarının nereye gittiğini bilmelisin:

- İndirmeler doğrudan siteden telefonuna gelir. Bazı siteler için VBKTube medya adresini **herkese açık yardımcı servislerden** ister ve bu servisler indirdiğin bağlantıyı görür: TikTok için `tikwm.com`, X için `api.fxtwitter.com`, `api.vxtwitter.com` ve Twitter'ın kendi syndication adresi, Instagram için yedek olarak `ddinstagram.com` ve `kkinstagram.com`. Bunu istemiyorsan bu siteleri VBKTube ile kullanma.
- Yeni VBKTube sürümlerini kontrol etmek ve yt-dlp'yi güncellemek için GitHub'a bağlanılır. Otomatik sürüm kontrolünü Ayarlar'dan kapatabilirsin.
- Dahili tarayıcının çerezleri cihazında kalır. Bir indirme için gerektiğinde VBKTube yalnızca o sitenin çerezlerini geçici bir dosyaya aktarır ve bu dosya indirme adımı bitince silinir.
- Uygulama verileri Android yedeklerine dahil edilmez.

## Bilinen sınırlamalar

- Siteler sık değişir; bugün çalışan bir indirici yarın bozulabilir. Bir bağlantı çalışmayı bırakırsa önce **Ayarlar**'dan yt-dlp'yi güncelle, sonra bir issue aç.
- İngilizce modda Instagram, X, TikTok ve Reddit için bazı biçim adları hâlâ Türkçe görünür. Bu görünüşle ilgilidir ve sonraki bir sürümde düzeltilecek.
- Instagram hikâyeleri ve bazı özel içerikler için dahili tarayıcıda bir kez giriş yapmak gerekir.

## Sorumluluk Reddi

VBKTube, **kişisel kullanım** ve indirme hakkına sahip olduğun içerikler içindir; örneğin kendi yüklemelerin, kamu malı ya da Creative Commons içerikler veya sahibinin indirmeye izin verdiği içerikler.

- Telif hakkı yasalarına ve kullandığın sitelerin hizmet şartlarına uymak senin sorumluluğundadır. İndirme işlemi bir sitenin şartlarına aykırı olabilir.
- VBKTube; YouTube, Google, Instagram, Meta, TikTok, ByteDance, X, Reddit veya başka hiçbir hizmetle **bağlantılı değildir, onlar tarafından onaylanmamıştır ve desteklenmemektedir**. Tüm ticari markalar sahiplerine aittir.
- Yazılım, [lisansta](LICENSE) açıklandığı üzere hiçbir garanti verilmeksizin "olduğu gibi" sunulur.

## Katkıda Bulunanlar

VBKTube şu açık kaynaklı projeler üzerine kuruludur:

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (GPL-3.0) aracılığıyla
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0)
- [FFmpeg](https://ffmpeg.org/) (LGPL/GPL) ve [aria2](https://aria2.github.io/) (GPL-2.0)
- AndroidX, Material Components, Room, OkHttp, Glide ve ExoPlayer (Apache-2.0)

## Lisans

VBKTube özgür yazılımdır ve [GNU Genel Kamu Lisansı v3.0](LICENSE) ile lisanslanmıştır.
