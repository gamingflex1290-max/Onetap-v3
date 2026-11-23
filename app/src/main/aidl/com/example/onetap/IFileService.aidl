package com.example.onetap;

interface IFileService {
    void destroy() = 16777114;
    String copy(String src, String dst) = 1;
    String move(String src, String dst) = 2;
    String delete(String path) = 3;
    String zip(String src, String zipPath) = 4;
    String unzip(String zipPath, String dstDir) = 5;
    boolean exists(String path) = 6;
    String unzipSub(String zipPath, String sub, String marker, String dstDir) = 7;
    String sha1(String path) = 8;
}
