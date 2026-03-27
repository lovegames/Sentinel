var app = angular.module('sentinelDashboardApp');

app.controller('MetricCtl', ['$scope', '$stateParams', 'MetricService', '$interval', '$timeout',
  function ($scope, $stateParams, MetricService, $interval, $timeout) {
	$scope.charts = [];
    $scope.endTime = new Date();
    $scope.startTime = new Date();
    $scope.startTime.setMinutes($scope.endTime.getMinutes() - 5);
    $scope.timeRangeType = '5m';  // 当前选中的时间范围类型，默认5分钟
    $scope.isAutoRefresh = true;   // 是否自动刷新模式

    // 格式化日期为 datetime-local 输入框格式 (YYYY-MM-DDTHH:mm:ss)
    function formatDateTimeLocal(date) {
      var d = new Date(date);
      var year = d.getFullYear();
      var month = ('0' + (d.getMonth() + 1)).slice(-2);
      var day = ('0' + d.getDate()).slice(-2);
      var hours = ('0' + d.getHours()).slice(-2);
      var minutes = ('0' + d.getMinutes()).slice(-2);
      var seconds = ('0' + d.getSeconds()).slice(-2);
      return year + '-' + month + '-' + day + 'T' + hours + ':' + minutes + ':' + seconds;
    }

    function formatDate(date) {
      return moment(date).format('YYYY/MM/DD HH:mm:ss');
    }

    // 快捷时间范围选择
    $scope.setTimeRange = function(type) {
      $scope.timeRangeType = type;
      $scope.isAutoRefresh = true;  // 选择快捷时间，恢复自动刷新
      $scope.endTime = new Date();
      $scope.startTime = new Date();

      switch(type) {
        case '5m':
          $scope.startTime.setMinutes($scope.endTime.getMinutes() - 5);
          break;
        case '30m':
          $scope.startTime.setMinutes($scope.endTime.getMinutes() - 30);
          break;
        case '1h':
          $scope.startTime.setHours($scope.endTime.getHours() - 1);
          break;
        case '6h':
          $scope.startTime.setHours($scope.endTime.getHours() - 6);
          break;
        case '12h':
          $scope.startTime.setHours($scope.endTime.getHours() - 12);
          break;
        case '1d':
          $scope.startTime.setDate($scope.endTime.getDate() - 1);
          break;
        case '2d':
          $scope.startTime.setDate($scope.endTime.getDate() - 2);
          break;
      }

      $scope.reInitIdentityDatas();
    };

    // 自定义时间变化时清除快捷选择状态
    $scope.onStartTimeChange = function() {
      $scope.timeRangeType = 'custom';
      $scope.isAutoRefresh = false;  // 用户手动修改，停止自动刷新的时间更新
    };
    $scope.onEndTimeChange = function() {
      $scope.timeRangeType = 'custom';
      $scope.isAutoRefresh = false;  // 用户手动修改，停止自动刷新的时间更新
    };

    $scope.app = $stateParams.app;
    // 数据自动刷新频率
    var DATA_REFRESH_INTERVAL = 1000 * 10;

    $scope.servicePageConfig = {
      pageSize: 6,
      currentPageIndex: 1,
      totalPage: 1,
      totalCount: 0,
    };
    $scope.servicesChartConfigs = [];

    $scope.pageChanged = function (newPageNumber) {
      $scope.servicePageConfig.currentPageIndex = newPageNumber;
      $scope.reInitIdentityDatas();
    };

    var searchT;
    $scope.searchService = function () {
      $timeout.cancel(searchT);
      searchT = $timeout(function () {
        $scope.reInitIdentityDatas();
      }, 600);
    }

    var intervalId;
    $scope.reInitIdentityDatas = function() {
      $interval.cancel(intervalId);
      queryIdentityDatas();
      intervalId = $interval(function () {
        queryIdentityDatas();
      }, DATA_REFRESH_INTERVAL);
    };
    $scope.reInitIdentityDatas();

    $scope.$on('$destroy', function () {
      $interval.cancel(intervalId);
    });
    $scope.initAllChart = function () {
      //revoke useless charts positively
      while($scope.charts.length > 0) {
      	let chart = $scope.charts.pop();
      	chart.destroy();
      }
      $.each($scope.metrics, function (idx, metric) {
        if (idx == $scope.metrics.length - 1) {
          return;
        }
        // Skip chart rendering if no data
        if (!metric.data || metric.data.length === 0) {
          return;
        }
        const chart = new G2.Chart({
          container: 'chart' + idx,
          forceFit: true,
          width: 100,
          height: 250,
          padding: [10, 30, 70, 50]
        });
        $scope.charts.push(chart);
        var maxQps = 0;
        for (var i in metric.data) {
          var item = metric.data[i];
          if (item.passQps > maxQps) {
            maxQps = item.passQps;
          }
          if (item.blockQps > maxQps) {
            maxQps = item.blockQps;
          }
        }
        chart.source(metric.data);
        chart.scale('timestamp', {
          type: 'time',
          mask: 'YYYY-MM-DD HH:mm:ss'
        });
        chart.scale('passQps', {
          min: 0,
          max: maxQps,
          fine: true,
          alias: '通过 QPS'
          // max: 10
        });
        chart.scale('blockQps', {
          min: 0,
          max: maxQps,
          fine: true,
          alias: '拒绝 QPS',
        });
        chart.scale('rt', {
          min: 0,
          fine: true,
        });
        chart.axis('rt', {
          grid: null,
          label: null
        });
        chart.axis('blockQps', {
          grid: null,
          label: null
        });

        chart.axis('timestamp', {
          label: {
            textStyle: {
              textAlign: 'center', // 文本对齐方向，可取值为： start center end
              fill: '#404040', // 文本的颜色
              fontSize: '11', // 文本大小
              //textBaseline: 'top', // 文本基准线，可取 top middle bottom，默认为middle
            },
            autoRotate: false,
            formatter: function (text, item, index) {
              return text.substring(11, 11 + 5);
            }
          }
        });
        chart.legend({
          custom: true,
          position: 'bottom',
          allowAllCanceled: true,
          itemFormatter: function (val) {
            if ('passQps' === val) {
              return '通过 QPS';
            }
            if ('blockQps' === val) {
              return '拒绝 QPS';
            }
            return val;
          },
          items: [
            { value: 'passQps', marker: { symbol: 'hyphen', stroke: 'green', radius: 5, lineWidth: 2 } },
            { value: 'blockQps', marker: { symbol: 'hyphen', stroke: 'blue', radius: 5, lineWidth: 2 } },
            //{ value: 'rt', marker: {symbol: 'hyphen', stroke: 'gray', radius: 5, lineWidth: 2} },
          ],
          onClick: function (ev) {
            const item = ev.item;
            const value = item.value;
            const checked = ev.checked;
            const geoms = chart.getAllGeoms();
            for (var i = 0; i < geoms.length; i++) {
              const geom = geoms[i];
              if (geom.getYScale().field === value) {
                if (checked) {
                  geom.show();
                } else {
                  geom.hide();
                }
              }
            }
          }
        });
        chart.line().position('timestamp*passQps').size(1).color('green').shape('smooth');
        chart.line().position('timestamp*blockQps').size(1).color('blue').shape('smooth');
        //chart.line().position('timestamp*rt').size(1).color('gray').shape('smooth');
        G2.track(false);
        chart.render();
      });
    };

    $scope.metrics = [];
    $scope.emptyObjs = [];
    function queryIdentityDatas() {
      // 如果是自动刷新模式，更新时间范围
      if ($scope.isAutoRefresh && $scope.timeRangeType !== 'custom') {
        $scope.endTime = new Date();
        $scope.startTime = new Date();
        switch($scope.timeRangeType) {
          case '5m':
            $scope.startTime.setMinutes($scope.endTime.getMinutes() - 5);
            break;
          case '30m':
            $scope.startTime.setMinutes($scope.endTime.getMinutes() - 30);
            break;
          case '1h':
            $scope.startTime.setHours($scope.endTime.getHours() - 1);
            break;
          case '6h':
            $scope.startTime.setHours($scope.endTime.getHours() - 6);
            break;
          case '12h':
            $scope.startTime.setHours($scope.endTime.getHours() - 12);
            break;
          case '1d':
            $scope.startTime.setDate($scope.endTime.getDate() - 1);
            break;
          case '2d':
            $scope.startTime.setDate($scope.endTime.getDate() - 2);
            break;
        }
      }

      var params = {
        app: $scope.app,
        pageIndex: $scope.servicePageConfig.currentPageIndex,
        pageSize: $scope.servicePageConfig.pageSize,
        desc: $scope.isDescOrder,
        searchKey: $scope.serviceQuery,
        startTime: $scope.startTime.getTime(),
        endTime: $scope.endTime.getTime()
      };
      console.log('queryIdentityDatas - isAutoRefresh:', $scope.isAutoRefresh, 'timeRangeType:', $scope.timeRangeType, 'startTime:', new Date(params.startTime), 'endTime:', new Date(params.endTime));
      MetricService.queryAppSortedIdentities(params).success(function (data) {
        $scope.metrics = [];
        $scope.emptyObjs = [];
        if (data.code === 0 && data.data) {
          var metricsObj = data.data.metric;
          var identityNames = Object.keys(metricsObj);
          if (identityNames.length < 1) {
            $scope.emptyServices = true;
          } else {
            $scope.emptyServices = false;
          }
          $scope.servicePageConfig.totalPage = data.data.totalPage;
          $scope.servicePageConfig.pageSize = data.data.pageSize;
          var totalCount = data.data.totalCount;
          $scope.servicePageConfig.totalCount = totalCount;
          for (i = 0; i < totalCount; i++) {
            $scope.emptyObjs.push({});
          }
          $.each(identityNames, function (idx, identityName) {
            var identityDatas = metricsObj[identityName];
            var metrics = {};
            metrics.resource = identityName;
            // metrics.data = identityDatas;
            metrics.data = fillZeros(identityDatas);
            metrics.shortData = lastOfArray(identityDatas, 6);
            $scope.metrics.push(metrics);
          });
          // push an empty element in the last, for ng-init reasons.
          $scope.metrics.push([]);
        } else {
          $scope.emptyServices = true;
          console.log(data.msg);
        }
      });
    };
    function fillZeros(metricData) {
      if (!metricData || metricData.length == 0) {
        return [];
      }
      var filledData = [];
      filledData.push(metricData[0]);
      var lastTime = metricData[0].timestamp / 1000;
      for (var i = 1; i < metricData.length; i++) {
        var curTime = metricData[i].timestamp / 1000;
        if (curTime > lastTime + 1) {
          for (var j = lastTime + 1; j < curTime; j++) {
            filledData.push({
                "timestamp": j * 1000,
                "passQps": 0,
                "blockQps": 0,
                "successQps": 0,
                "exception": 0,
                "rt": 0,
                "count": 0
            })
          }
        }
        filledData.push(metricData[i]);
        lastTime = curTime;
      }
      return filledData;
    }
    function lastOfArray(arr, n) {
      if (!arr.length) {
        return [];
      }
      var rs = [];
      for (i = 0; i < n && i < arr.length; i++) {
        rs.push(arr[arr.length - 1 - i]);
      }
      return rs;
    }

    $scope.isDescOrder = true;
    $scope.setDescOrder = function () {
      $scope.isDescOrder = true;
      $scope.reInitIdentityDatas();
    }
    $scope.setAscOrder = function () {
      $scope.isDescOrder = false;
      $scope.reInitIdentityDatas();
    }
  }]);
