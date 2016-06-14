/*
 * Copyright (c) 2015 Wind River Systems, Inc.
 *
 * The right to copy, distribute, modify, or otherwise make use
 * of this software may be licensed only pursuant to the terms
 * of an applicable Wind River license agreement.
 */


#include "Accessory.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
}

#include <SDL.h>
#include <SDL_thread.h>

#define VID 0x18D1
#define PID 0x4EE2 // Nexus 5

#define WIN_WIDTH 180

int main() {
    // init accessory
    Accessory acc;
    if (!acc.init(VID, PID)) {
        fprintf(stderr, "Can't init accessory\n");
        return 1;
    }

    printf("AOA init succeed\n");

    // read video width/height
    unsigned char size_buf[4];
    int r = acc.readUsb(size_buf, 4);

    int width = ((int)size_buf[0] << 8) | ((int)size_buf[1]);
    int height = ((int)size_buf[2] << 8) | ((int)size_buf[3]);

    int win_width = WIN_WIDTH;
    int win_height = (WIN_WIDTH*height)/width;

    printf("video size = (%d,%d), window size = (%d,%d)\n", width, height, win_width, win_height);


    AVCodecContext *pCodecCtx = NULL;
    AVCodec *pCodec = NULL;
    AVFrame *pFrame = NULL;

    // init ffmpeg codec
    avcodec_register_all();
    pCodec=avcodec_find_decoder(AV_CODEC_ID_H264);
    if(pCodec==NULL) {
        fprintf(stderr, "Unsupported codec!\n");
        return 1;
    }

    pCodecCtx = avcodec_alloc_context3(pCodec);
    pCodecCtx->width = width;
    pCodecCtx->height = height;
    pCodecCtx->pix_fmt = AV_PIX_FMT_YUV420P;
    if(avcodec_open2(pCodecCtx, pCodec, NULL)<0) {
        fprintf(stderr, "codec open failed\n");
        return 1;
    }

    pFrame=av_frame_alloc();

    // init SDL
    if(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_TIMER)) {
        fprintf(stderr, "Could not initialize SDL - %s\n", SDL_GetError());
        exit(1);
    }
    SDL_Surface *screen = SDL_SetVideoMode(win_width, win_height, 0, 0);
    if(!screen) {
        fprintf(stderr, "SDL: could not set video mode - exiting\n");
        exit(1);
    }
    SDL_Overlay *bmp = SDL_CreateYUVOverlay(width, height, SDL_YV12_OVERLAY, screen);

    // init swscaler
    struct SwsContext *sws_ctx = sws_getContext(pCodecCtx->width, pCodecCtx->height, pCodecCtx->pix_fmt, win_width, win_height, AV_PIX_FMT_YUV420P,  SWS_BILINEAR, NULL,  NULL, NULL);

    SDL_Rect rect;
    rect.x = 0;
    rect.y = 0;
    rect.w = pCodecCtx->width;
    rect.h = pCodecCtx->height;

    AVPacket packet;
    int gotFrame = 1;
    unsigned char buf[4];
    while(1) {
        r = acc.readUsb(buf, 4);
        if (r < 0) {
            fprintf(stderr, "EOF\n");
            break;
        }

        int length = (((int) buf[0]) << 24) | (((int) buf[1]) << 16) | (((int) buf[2]) << 8) | buf[3];

        char *packet_buf = (char *)av_malloc(length);

        //printf("offset = %d, length = %d\n", offset, length);
        r = acc.readUsb((unsigned char *)packet_buf, length);
        //printf("requested length = %d, actual length = %d\n", length, r);

        /*
        char needle[] = { 0, 0, 0, 1};
        char *p = packet_buf;
        char *pe = packet_buf + length;
        while (p < pe) {
            char *f = (char *)memmem(p+1, (pe-p-1), needle, sizeof(needle));
            if (f == NULL) {
                f = pe;
            }
            for (int i=0; i<(f-p) && i < 80; i++)
                printf("%02x ", (int)p[i] & 0xff);
            printf("\n");

            p = f;
        }
         */

        memset(&packet, 0, sizeof(packet));
        av_packet_from_data(&packet, (uint8_t *)packet_buf, length);
        avcodec_decode_video2(pCodecCtx, pFrame, &gotFrame, &packet);
        //av_free(packet_buf);

        printf("gotFrame = %d\n", gotFrame);
        if(gotFrame) {
            SDL_LockYUVOverlay(bmp);

            AVPicture pict;
            pict.data[0] = bmp->pixels[0];
            pict.data[1] = bmp->pixels[2];
            pict.data[2] = bmp->pixels[1];

            pict.linesize[0] = bmp->pitches[0];
            pict.linesize[1] = bmp->pitches[2];
            pict.linesize[2] = bmp->pitches[1];

            // Convert the image into YUV format that SDL uses
            sws_scale(sws_ctx, (uint8_t const *const *) pFrame->data,
                      pFrame->linesize, 0, pCodecCtx->height,
                      pict.data, pict.linesize);

            SDL_UnlockYUVOverlay(bmp);
            SDL_DisplayYUVOverlay(bmp, &rect);

        }
        av_free_packet(&packet);
    }

    return 0;
}