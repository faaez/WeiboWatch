# -*- coding: utf-8 -*-

import mechanize
import json
import string 
import re
import sys
from datetime import datetime
from time import sleep
from pymongo import MongoClient
import urllib
from elasticsearch import Elasticsearch
global post_datetime
global max_value
from bs4 import BeautifulSoup

client = MongoClient()
collection = client.sinaweibo.npc
datelimit = datetime.strptime("20140301", "%Y%m%d")
post_datetime = datetime.now()
query = urllib.quote(u'十二届全国人大二次会议'.encode('utf-8'))
query += '+'+urllib.quote(u'两会'.encode('utf-8'))
query += '+'+urllib.quote(u'人大会议'.encode('utf-8'))

base_url = "https://freeweibo.com/get-from-cache.php?latest=&q="+query
browser = mechanize.Browser()

es = Elasticsearch(['teneo.cloudapp.net:9200'])

topic_index = 'weibowatch_tech'


def process_batch(batch):
    global post_datetime
    global max_value

    for content in batch["messages"]:
        
        max_value = batch["messages"][content]["order_by_value"]
        date = batch["messages"][content]["created_at"]
            
        # print batch["messages"][content]
        if string.find(date, ">2013") != -1:
            continue
        parsed_date = re.sub("[^0-9]", "", date[string.find(date, ">")+1:])[0:8]
        parsed_date = parsed_date[0:4]+"-"+parsed_date[4:6]+"-"+parsed_date[6:]
        batch["messages"][content]["date"] = parsed_date
        post_datetime = datetime.strptime(parsed_date, "%Y-%m-%d")
        batch["messages"][content]["html_text"] = batch["messages"][content]["text"]
        batch["messages"][content]["text"] = BeautifulSoup(batch["messages"][content]["text"]).getText()
        batch["messages"][content]["id"] = batch["messages"][content]["status_id"]
        del batch["messages"][content]["status_id"]
        # collection.insert(batch["messages"][content])
        # print content
        # print json.dumps(batch["messages"][content])
        es.index(index=topic_index, doc_type='post', body=batch["messages"][content])

if len(sys.argv) == 1:
    process_batch(json.loads(browser.open(base_url).read()))
    
else:
    max_value = int(sys.argv[1])

while post_datetime > datelimit:
    sleep(0.5)
    process_batch(json.loads(browser.open(base_url+"&max-order-by-value="+str(max_value)).read()))