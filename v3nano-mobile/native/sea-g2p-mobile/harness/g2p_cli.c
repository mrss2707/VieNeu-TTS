// Reads UTF-8 lines (stdin) -> prints phonemes per line (stdout). Timing/RSS to stderr.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
#include "sea_g2p.h"
static double now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1e3+t.tv_nsec/1e6;}
static double rss_mb(void){struct rusage r;getrusage(RUSAGE_SELF,&r);
#ifdef __APPLE__
 return r.ru_maxrss/1048576.0;
#else
 return r.ru_maxrss/1024.0;
#endif
}
int main(int argc,char**argv){
  if(argc<2){fprintf(stderr,"usage: %s dict.bin [punc_norm=1]\n",argv[0]);return 2;}
  int pn=argc>2?atoi(argv[2]):1;
  double t0=now_ms(); sea_g2p*h=sea_g2p_open(argv[1]); double t1=now_ms();
  if(!h){fprintf(stderr,"open failed: %s\n",sea_g2p_last_error());return 1;}
  fprintf(stderr,"open_ms=%.2f rss_after_open_mb=%.1f\n",t1-t0,rss_mb());
  char*line=NULL;size_t cap=0;ssize_t n;double tot=0;long chars=0;int first=1;double firstms=0;
  while((n=getline(&line,&cap,stdin))>0){
    if(line[n-1]=='\n')line[--n]=0;
    double a=now_ms(); char*o=sea_g2p_phonemize(h,line,pn); double b=now_ms();
    if(first){firstms=b-a;first=0;}
    tot+=b-a;chars+=n;
    if(!o){printf("<<ERR:%s>>\n",sea_g2p_last_error());continue;}
    printf("%s\n",o); sea_g2p_string_free(o);
  }
  fprintf(stderr,"first_call_ms=%.2f total_ms=%.2f chars=%ld rss_end_mb=%.1f\n",firstms,tot,chars,rss_mb());
  sea_g2p_close(h);return 0;}
