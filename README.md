WeiboWatch
==========

A live monitor for Sina Weibo based on Matthias Nehlsen's excellent <a href="https://github.com/matthiasn/BirdWatch">BirdWatch</a> app.

The posts are crawled from http://freeweibo.com and contain both censored and uncensored content.

##Setup

Play Framework. You need a JVM on your machine. On a Mac the easiest way is to then install play using **[HomeBrew](http://brew.sh)**: 
 
    brew install play
    
If brew was installed on your machine already you want to run this first: 

    brew update
    brew upgrade

You also need ElasticSearch:
 
    brew install elasticsearch

    
You then run

    elasticsearch

BEWARE: this application has recently been upgraded to work with ElasticSearch v1.0.0. There have been breaking changes in the Percolation Query API (for the better, for sure) but because of these changes the latest version will not work with previous versions of ElasticSearch. If for some reason you cannot run v1.0.0 yet, you can check out an earlier commit of this application.
    
And inside the application folder:
    
    play run

##Configuration
Inside `conf/application.conf` you can change terms that the application on Freeweibo.com. Leaving the terms empty will collect all posts on Freeweibo.com. 

You may want to remove Google Analytics script in main.scala.html or adapt the Analytics setting in the application.conf according to your own needs.
