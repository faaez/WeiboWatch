# -*- coding: utf-8 -*-

import mechanize
import json
import string 
import re
import sys
from datetime import datetime
from time import sleep
from pymongo import MongoClient
import urllib, urllib2
from elasticsearch import Elasticsearch
global post_datetime
global max_value
from bs4 import BeautifulSoup

client = MongoClient()
collection = client.sinaweibo.npc

#date you want to go back to
datelimit = datetime.strptime("20131001", "%Y%m%d")
post_datetime = datetime.now()

#define query
query = urllib.quote(u''.encode('utf-8'))

base_url = "https://freeweibo.com/get-from-cache.php?latest=&q="+query
browser = mechanize.Browser()

es_server = 'localhost:9200'
topic_index = 'weibowatch_general'

es = Elasticsearch([es_server])

def process_batch(batch):
    global post_datetime
    global max_value

    for content in batch["messages"]:
        if "hidden" in batch["messages"][content].keys():
            continue
        tbp = {}
        max_value = batch["messages"][content]["order_by_value"]
        date = batch["messages"][content]["created_at"]
            
        # print batch["messages"][content]
        parsed_date = re.sub("[^0-9]", "", date[string.find(date, ">")+1:])[0:12]
        parsed_date = parsed_date[0:4]+"-"+parsed_date[4:6]+"-"+parsed_date[6:8]+" "+parsed_date[8:10]+":"+parsed_date[10:12]+" CST"
        post_datetime = datetime.strptime(parsed_date[0:10], "%Y-%m-%d")
        
        tbp["text"] = BeautifulSoup(batch["messages"][content]["text"]).getText()
        tbp["datetime"] = parsed_date
        tbp["id"] = batch["messages"][content]["id"]
        tbp["status_id"] = batch["messages"][content]["status_id"]
        tbp["profile_image_url"]= batch["messages"][content]["profile_image_url"]
        tbp["reposts_count"] = batch["messages"][content]["reposts_count"]
        tbp["hotness"] = batch["messages"][content]["hotness"]
        tbp["user_followers_count"] = batch["messages"][content]["user_followers_count"]
        tbp["user_id"] = batch["messages"][content]["user_id"]
        tbp["user_name"] = batch["messages"][content]["user_name"]
        tbp["censored"] = batch["messages"][content]["censored"]
        tbp["deleted"] = batch["messages"][content]["deleted"]
        tbp["order_by_value"] = batch["messages"][content]["order_by_value"]
	
        res = json.loads(browser.open('http://'+es_server+'/'+topic_index+'/_count?q=id:'+tbp["id"]).read())
        if int(res["count"]) == 0: 
            es.index(index=topic_index, doc_type='post', body=tbp)

if len(sys.argv) == 1:
    process_batch(json.loads(browser.open(base_url).read()))
    
else:
    max_value = int(sys.argv[1])

while post_datetime > datelimit:
    sleep(0.5)
    process_batch(json.loads(browser.open(base_url+"&max-order-by-value="+str(max_value)).read()))
