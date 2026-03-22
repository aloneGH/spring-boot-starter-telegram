package dev.voroby.telegram.music.service.netease;

import java.util.List;

public class SearchMusicResult {
    private Result result;
    private Long code;

    // 无参构造
    public SearchMusicResult() {
    }

    // Getter 和 Setter
    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public Long getCode() {
        return code;
    }

    public void setCode(Long code) {
        this.code = code;
    }

    public static class Result {
        private List<Song> songs;
        private Boolean hasMore;
        private Long songCount;

        public Result() {
        }

        public List<Song> getSongs() {
            return songs;
        }

        public void setSongs(List<Song> songs) {
            this.songs = songs;
        }

        public Boolean getHasMore() {
            return hasMore;
        }

        public void setHasMore(Boolean hasMore) {
            this.hasMore = hasMore;
        }

        public Long getSongCount() {
            return songCount;
        }

        public void setSongCount(Long songCount) {
            this.songCount = songCount;
        }
    }

    public static class Song {
        private Long id;
        private String name;
        private List<Artist> artists;
        private Album album;
        private Long duration;
        private Long copyrightId;
        private Long status;
        private List<String> alias;
        private Long rtype;
        private Long ftype;
        private Long mvid;
        private Long fee;
        private String rUrl;
        private Long mark;

        public Song() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Artist> getArtists() {
            return artists;
        }

        public void setArtists(List<Artist> artists) {
            this.artists = artists;
        }

        public Album getAlbum() {
            return album;
        }

        public void setAlbum(Album album) {
            this.album = album;
        }

        public Long getDuration() {
            return duration;
        }

        public void setDuration(Long duration) {
            this.duration = duration;
        }

        public Long getCopyrightId() {
            return copyrightId;
        }

        public void setCopyrightId(Long copyrightId) {
            this.copyrightId = copyrightId;
        }

        public Long getStatus() {
            return status;
        }

        public void setStatus(Long status) {
            this.status = status;
        }

        public List<String> getAlias() {
            return alias;
        }

        public void setAlias(List<String> alias) {
            this.alias = alias;
        }

        public Long getRtype() {
            return rtype;
        }

        public void setRtype(Long rtype) {
            this.rtype = rtype;
        }

        public Long getFtype() {
            return ftype;
        }

        public void setFtype(Long ftype) {
            this.ftype = ftype;
        }

        public Long getMvid() {
            return mvid;
        }

        public void setMvid(Long mvid) {
            this.mvid = mvid;
        }

        public Long getFee() {
            return fee;
        }

        public void setFee(Long fee) {
            this.fee = fee;
        }

        public String getrUrl() {
            return rUrl;
        }

        public void setrUrl(String rUrl) {
            this.rUrl = rUrl;
        }

        public Long getMark() {
            return mark;
        }

        public void setMark(Long mark) {
            this.mark = mark;
        }
    }

    public static class Artist {
        private Long id;
        private String name;
        private String picUrl;
        private List<String> alias;
        private Long albumSize;
        private Long musicSize;
        private Long picId;
        private String img1v1Url;
        private Long img1v1;

        public Artist() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPicUrl() {
            return picUrl;
        }

        public void setPicUrl(String picUrl) {
            this.picUrl = picUrl;
        }

        public List<String> getAlias() {
            return alias;
        }

        public void setAlias(List<String> alias) {
            this.alias = alias;
        }

        public Long getAlbumSize() {
            return albumSize;
        }

        public void setAlbumSize(Long albumSize) {
            this.albumSize = albumSize;
        }

        public Long getMusicSize() {
            return musicSize;
        }

        public void setMusicSize(Long musicSize) {
            this.musicSize = musicSize;
        }

        public Long getPicId() {
            return picId;
        }

        public void setPicId(Long picId) {
            this.picId = picId;
        }

        public String getImg1v1Url() {
            return img1v1Url;
        }

        public void setImg1v1Url(String img1v1Url) {
            this.img1v1Url = img1v1Url;
        }

        public Long getImg1v1() {
            return img1v1;
        }

        public void setImg1v1(Long img1v1) {
            this.img1v1 = img1v1;
        }
    }

    public static class Album {
        private Long id;
        private String name;
        private Artist artist;
        private Long publishTime;
        private Long size;
        private Long picId;

        public Album() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Artist getArtist() {
            return artist;
        }

        public void setArtist(Artist artist) {
            this.artist = artist;
        }

        public Long getPublishTime() {
            return publishTime;
        }

        public void setPublishTime(Long publishTime) {
            this.publishTime = publishTime;
        }

        public Long getSize() {
            return size;
        }

        public void setSize(Long size) {
            this.size = size;
        }

        public Long getPicId() {
            return picId;
        }

        public void setPicId(Long picId) {
            this.picId = picId;
        }
    }
}


